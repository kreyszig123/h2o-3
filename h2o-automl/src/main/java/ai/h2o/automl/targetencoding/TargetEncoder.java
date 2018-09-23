package ai.h2o.automl.targetencoding;

import water.*;
import water.fvec.*;
import water.fvec.task.FillNAWithValueTask;
import water.rapids.Merge;
import water.rapids.Rapids;
import water.rapids.Val;
import water.rapids.ast.prims.mungers.AstGroup;
import water.util.FrameUtils;
import water.util.Log;
import water.util.TwoDimTable;

import java.util.*;

import static water.util.FrameUtils.getColumnIndexByName;

public class TargetEncoder {

    BlendingParams blendingParams;

    public TargetEncoder (BlendingParams blendingParams) {
      this.blendingParams = blendingParams;
    }

    public TargetEncoder () {
      this.blendingParams = new BlendingParams(20, 10);
    }

    public static class DataLeakageHandlingStrategy {
        public static final byte LeaveOneOut  =  0;
        public static final byte KFold  =  1;
        public static final byte None  =  2;
    }

    /**
     * @param columnNamesToEncode names of columns to apply target encoding to
     * @param targetColumnName target column index
     * @param foldColumnName should contain index of column as String. TODO Change later into suitable type.
     */
    //TODO do we need to do this preparation before as a separate phase? because we are grouping twice.
    //TODO At least it seems that way in the case of KFold. But even if we need to preprocess for other types of TE calculations... we should not affect KFOLD case anyway.
    public Map<String, Frame> prepareEncodingMap(Frame data, String[] columnNamesToEncode, String targetColumnName, String foldColumnName) {

        // Validate input data. Not sure whether we should check some of these.
        // It will become clear when we decide if TE is going to be exposed to user or only integrated into AutoML's pipeline

        if(data == null) throw new IllegalStateException("Argument 'data' is missing, with no default");

        if(columnNamesToEncode == null || columnNamesToEncode.length == 0)
            throw new IllegalStateException("Argument 'columnsToEncode' is not defined or empty");

        if(targetColumnName == null || targetColumnName.equals(""))
            throw new IllegalStateException("Argument 'target' is missing, with no default");

        if(! checkAllTEColumnsAreCategorical(data, columnNamesToEncode))
            throw new IllegalStateException("Argument 'columnsToEncode' should contain only names of categorical columns");

        if(Arrays.asList(columnNamesToEncode).contains(targetColumnName)) {
            throw new IllegalArgumentException("Columns for target encoding contain target column.");
        }

        int targetIndex = getColumnIndexByName(data, targetColumnName);

        Frame  dataWithoutNAsForTarget = filterOutNAsFromTargetColumn(data, targetIndex);

        Frame dataWithEncodedTarget = ensureTargetColumnIsNumericOrBinaryCategorical(dataWithoutNAsForTarget, targetIndex);

        Map<String, Frame> columnToEncodingMap = new HashMap<String, Frame>();

        for ( String teColumnName: columnNamesToEncode) { // TODO maybe we can do it in parallel
            Frame teColumnFrame = null;
            int colIndex = getColumnIndexByName(dataWithEncodedTarget, teColumnName);
            if (foldColumnName == null) {
              teColumnFrame = groupThenAggregateForNumeratorAndDenominator(dataWithEncodedTarget, new int[]{colIndex}, targetIndex);
            } else {
              int foldColumnIndex = getColumnIndexByName(dataWithEncodedTarget, foldColumnName);
              teColumnFrame = groupThenAggregateForNumeratorAndDenominator(dataWithEncodedTarget, new int[]{colIndex, foldColumnIndex}, targetIndex);
            }
            teColumnFrame.renameColumn( "sum_"+ targetColumnName, "numerator");
            teColumnFrame.renameColumn( "nrow", "denominator");

            columnToEncodingMap.put(teColumnName, teColumnFrame);
        }

        dataWithEncodedTarget.delete();
        dataWithoutNAsForTarget.delete();

        return columnToEncodingMap;
    }

    Frame groupThenAggregateForNumeratorAndDenominator(Frame fr, int[] groupByColumns, int targetIndex) {
      AstGroup.AGG[] aggs = new AstGroup.AGG[2];

      AstGroup.NAHandling na = AstGroup.NAHandling.ALL;
      aggs[0] = new AstGroup.AGG(AstGroup.FCN.sum, targetIndex, na, (int) fr.vec(targetIndex).max() + 1);
      aggs[1] = new AstGroup.AGG(AstGroup.FCN.nrow, targetIndex, na, (int) fr.vec(targetIndex).max() + 1);

      Frame result = new AstGroup().performGroupingWithAggregations(fr, groupByColumns, aggs, -1).getFrame();
      return FrameUtils.register(result);
    }

    Frame ensureTargetColumnIsNumericOrBinaryCategorical(Frame data, String targetColumnName) {
        return ensureTargetColumnIsNumericOrBinaryCategorical(data, getColumnIndexByName(data, targetColumnName));
    };

    Frame ensureTargetColumnIsNumericOrBinaryCategorical(Frame data, int targetIndex) {
        if (data.vec(targetIndex).isCategorical()){
            Vec targetVec = data.vec(targetIndex);
            if(targetVec.cardinality() == 2) {
                return transformBinaryTargetColumn(data, targetIndex);
            }
            else {
                throw new IllegalStateException("`target` must be a binary vector");
            }
        }
        else {
            if(! data.vec(targetIndex).isNumeric()) {
                throw new IllegalStateException("`target` must be a numeric or binary vector");
            }
            return data;
        }
    };

    Map<String, Frame> prepareEncodingMap(Frame data, int[] columnIndexesToEncode, int targetIndex) {
        String [] columnNamesToEncode = getColumnNamesBy(data, columnIndexesToEncode);
        return prepareEncodingMap(data, columnNamesToEncode, getColumnNameBy(data, targetIndex), null);
    }

    Map<String, Frame> prepareEncodingMap(Frame data, int[] columnIndexesToEncode, int targetIndex, String foldColumnName) {
        String [] columnNamesToEncode = getColumnNamesBy(data, columnIndexesToEncode);
        return prepareEncodingMap(data, columnNamesToEncode, getColumnNameBy(data, targetIndex), foldColumnName);
    }

    Map<String, Frame> prepareEncodingMap(Frame data, int[] columnIndexesToEncode, int targetIndex, int foldColumnIndex) {
        String [] columnNamesToEncode = getColumnNamesBy(data, columnIndexesToEncode);
        String foldColumnName = getColumnNameBy(data, foldColumnIndex);
        return prepareEncodingMap(data, columnNamesToEncode, getColumnNameBy(data, targetIndex), foldColumnName);
    }

    String[] getColumnNamesBy(Frame data, int[] columnIndexes) {
        String [] allColumnNames = data._names.clone();
        ArrayList<String> columnNames = new ArrayList<String>();

        for(int idx : columnIndexes) {
            columnNames.add(allColumnNames[idx]);
        }
        return columnNames.toArray(new String[columnIndexes.length]);
    }

    String getColumnNameBy(Frame data, int columnIndex) {
        String [] allColumnNames = data._names.clone();
        return allColumnNames[columnIndex];
    }

    private Frame execRapidsAndGetFrame(String astTree) {
        Val val = Rapids.exec(astTree);
        Frame res = val.getFrame();
        res._key = Key.make();
        DKV.put(res);
        return res;
    }

    // We might want to introduce parameter that will change this behaviour. We can treat NA's as extra class.
    Frame filterOutNAsFromTargetColumn(Frame data, int targetColumnIndex) {
        return data.filterOutNAsInColumn(targetColumnIndex);
    }

    Frame transformBinaryTargetColumn(Frame data, int targetIndex)  {
        return data.vectorAsQuasiBinomial(targetIndex);
    }

    Frame getOutOfFoldData(Frame encodingMap, String foldColumnName, long currentFoldValue)  {
        int foldColumnIndexInEncodingMap = getColumnIndexByName(encodingMap, foldColumnName);
        return encodingMap.filterNotByValue(foldColumnIndexInEncodingMap, currentFoldValue);
    }

    long[] getUniqueValuesOfTheFoldColumn(Frame data, int columnIndex) {
        Vec uniqueValues = data.uniqueValuesBy(columnIndex).vec(0);
        long numberOfUniqueValues = uniqueValues.length();
        assert numberOfUniqueValues <= Integer.MAX_VALUE : "Number of unique values exceeded Integer.MAX_VALUE";

        int length = (int) numberOfUniqueValues; // We assume that fold column should not has that many different values and we will fit into node's memory.
        long[] uniqueValuesArr = new long[length];
        for(int i = 0; i < uniqueValues.length(); i++) {
            uniqueValuesArr[i] = uniqueValues.at8(i);
        }
        uniqueValues.remove();
        return uniqueValuesArr;
    }

    private boolean checkAllTEColumnsAreCategorical(Frame data, String[] columnsToEncode)  {
        for( String columnName : columnsToEncode) {
            int columnIndex = getColumnIndexByName(data, columnName);
            if(! data.vec(columnIndex).isCategorical()) return false;
        }
        return true;
    }

    Frame groupByTEColumnAndAggregate(Frame data, int teColumnIndex) {
      int numeratorColumnIndex = getColumnIndexByName(data, "numerator");
      int denominatorColumnIndex = getColumnIndexByName(data, "denominator");
      AstGroup.AGG[] aggs = new AstGroup.AGG[2];

      AstGroup.NAHandling na = AstGroup.NAHandling.ALL;
      aggs[0] = new AstGroup.AGG(AstGroup.FCN.sum, numeratorColumnIndex, na, (int) data.vec(numeratorColumnIndex).max() + 1);
      aggs[1] = new AstGroup.AGG(AstGroup.FCN.sum, denominatorColumnIndex, na, (int) data.vec(denominatorColumnIndex).max() + 1);

      Frame result = new AstGroup().performGroupingWithAggregations(data, new int[]{teColumnIndex}, aggs, -1).getFrame();
      return FrameUtils.register(result);
    }

    Frame rBind(Frame a, Frame b) {
        if(a == null) {
            assert b != null;
            return b;
        } else {
            String tree = String.format("(rbind %s %s)", a._key, b._key);
            return execRapidsAndGetFrame(tree);
        }
    }

    Frame mergeByTEAndFoldColumns(Frame a, Frame holdoutEncodeMap, int teColumnIndexOriginal, int foldColumnIndexOriginal, int teColumnIndex) {
      int foldColumnIndexInEncodingMap = getColumnIndexByName(holdoutEncodeMap, "foldValueForMerge");
      return merge(a, holdoutEncodeMap, new int[]{teColumnIndexOriginal, foldColumnIndexOriginal}, new int[]{teColumnIndex, foldColumnIndexInEncodingMap});
    }

    static class GCForceTask extends MRTask<GCForceTask> {
      @Override
      protected void setupLocal() {
        System.gc();
      }
    }

    // Custom extract from AstMerge's implementation for a particular case `(merge l r TRUE FALSE [] [] 'auto' )`
    Frame merge(Frame l, Frame r, int[] byLeft, int[] byRite) {
      boolean allLeft = true;
      // See comments in the original implementation in AstMerge.java
      new GCForceTask().doAllNodes();

      int ncols = byLeft.length;
      l.moveFirst(byLeft);
      r.moveFirst(byRite);

      int[][] id_maps = new int[ncols][];
      for (int i = 0; i < ncols; i++) {
        Vec lv = l.vec(i);
        Vec rv = r.vec(i);

        if (lv.isCategorical()) {
          assert rv.isCategorical();
          id_maps[i] = CategoricalWrappedVec.computeMap(lv.domain(), rv.domain());
        }
      }
      int cols[] = new int[ncols];
      for (int i = 0; i < ncols; i++) cols[i] = i;
      return FrameUtils.register(Merge.merge(l, r, cols, cols, allLeft, id_maps));
    }

    Frame mergeByTEColumn(Frame a, Frame b, int teColumnIndexOriginal, int teColumnIndex) {
      return merge(a, b, new int[]{teColumnIndexOriginal}, new int[]{teColumnIndex});
    }

    Frame imputeWithMean(Frame a, int columnIndex) {
      Vec vec = a.vec(columnIndex);
      assert vec.get_type() == Vec.T_NUM : "Imputation of mean value is supported only for numerical vectors.";
      double mean = vec.mean();
      long numberOfNAs = vec.naCnt();
      if (numberOfNAs > 0) {
        new FillNAWithValueTask(columnIndex, mean).doAll(a);
        Log.warn(String.format("Frame with id = %s was imputed with mean = %f ( %d rows were affected)", a._key, mean, numberOfNAs));
      }
      return a;
    }

    double calculatePriorMean(Frame fr) {
        Vec numeratorVec = fr.vec("numerator");
        Vec denominatorVec = fr.vec("denominator");
        return numeratorVec.mean() / denominatorVec.mean();
    }

    Frame calculateAndAppendBlendedTEEncoding(Frame fr, Frame encodingMap, String targetColumnName, String appendedColumnName) {
      int numeratorIndex = getColumnIndexByName(fr, "numerator");
      int denominatorIndex = getColumnIndexByName(fr, "denominator");

      double globalMeanForTargetClass = calculatePriorMean(encodingMap); // TODO since target column is the same for all categorical columns we are trying to encode we can compute global mean only once.
      System.out.print("Global mean for blending = " + globalMeanForTargetClass);

      Frame frameWithBlendedEncodings = new CalcEncodingsWithBlending(numeratorIndex, denominatorIndex, globalMeanForTargetClass, blendingParams).doAll(Vec.T_NUM, fr).outputFrame();
      fr.add(appendedColumnName, frameWithBlendedEncodings.anyVec());
      return fr;
    }

    static class CalcEncodingsWithBlending extends MRTask<CalcEncodingsWithBlending> {
      private double priorMean;
      private int numeratorIdx;
      private int denominatorIdx;
      private BlendingParams blendingParams;

      CalcEncodingsWithBlending(int numeratorIdx, int denominatorIdx, double priorMean, BlendingParams blendingParams) {
        this.numeratorIdx = numeratorIdx;
        this.denominatorIdx = denominatorIdx;
        this.priorMean = priorMean;
        this.blendingParams = blendingParams;
      }

      @Override
      public void map(Chunk cs[], NewChunk ncs[]) {
        Chunk num = cs[numeratorIdx];
        Chunk den = cs[denominatorIdx];
        NewChunk nc = ncs[0];
        for (int i = 0; i < num._len; i++) {
          if (num.isNA(i) || den.isNA(i))
            nc.addNA();
          else if (den.at8(i) == 0) {
            System.out.println("Denominator is zero. Imputing with priorMean = " + priorMean);
            nc.addNum(priorMean);
          } else {
            double numberOfRowsInCurrentCategory = den.atd(i);
            double lambda = 1.0 / (1 + Math.exp((blendingParams.getK() - numberOfRowsInCurrentCategory) / blendingParams.getF()));
            double posteriorMean = num.atd(i) / den.atd(i);
            double blendedValue = lambda * posteriorMean + (1 - lambda) * priorMean;
            nc.addNum(blendedValue);
          }
        }
      }
    }

    Frame calculateAndAppendTEEncoding(Frame fr, Frame encodingMap, String targetColumnName, String appendedColumnName) {
      int numeratorIndex = getColumnIndexByName(fr, "numerator");
      int denominatorIndex = getColumnIndexByName(fr, "denominator");

      double globalMeanForTargetClass = calculatePriorMean(encodingMap); // we can only operate on encodingsMap because `fr` could not have target column at all

      //I can do a trick with appending a column of zeroes and then we can map encodings into it.
      Frame frameWithEncodings = new CalcEncodings(numeratorIndex, denominatorIndex, globalMeanForTargetClass).doAll(Vec.T_NUM, fr).outputFrame();
      fr.add(appendedColumnName, frameWithEncodings.anyVec()); // Can we just add(append)? Would the order be preserved?
      return fr;
    }


    static class CalcEncodings extends MRTask<CalcEncodings> {
      private double globalMean;
      private int numeratorIdx;
      private int denominatorIdx;

      CalcEncodings(int numeratorIdx, int denominatorIdx, double globalMean) {
        this.numeratorIdx = numeratorIdx;
        this.denominatorIdx = denominatorIdx;
        this.globalMean = globalMean;
      }

      @Override
      public void map(Chunk cs[], NewChunk ncs[]) {
        Chunk num = cs[numeratorIdx];
        Chunk den = cs[denominatorIdx];
        NewChunk nc = ncs[0];
        for (int i = 0; i < num._len; i++) {
          if (num.isNA(i) || den.isNA(i))
            nc.addNA();
          else if (den.at8(i) == 0) {
            nc.addNum(globalMean);
          } else {
            double posteriorMean = num.atd(i) / den.atd(i);
            nc.addNum(posteriorMean);
          }
        }
      }
    }

    //TODO think about what value could be used for substitution ( maybe even taking into account target's value)
    private String getDenominatorIsZeroSubstitutionTerm(Frame fr, String targetColumnName, double globalMeanForTargetClass) {
      // This should happen only for Leave-One-Out case:
      // These groups have this singleness in common and we probably want to represent it somehow.
      // If we choose just global average then we just lose difference between single-row-groups that have different target values.
      // We can: 1) Group is so small that we even don't want to care about te_column's values.... just use Prior average.
      //         2) Count single-row-groups and calculate    #of_single_rows_with_target0 / #all_single_rows  ;  (and the same for target1)
      //TODO Introduce parameter for algorithm that will choose the way of calculating of the value that is being imputed.
      String denominatorIsZeroSubstitutionTerm;

      if(targetColumnName == null) { // When we calculating encodings for instances without target values.
        denominatorIsZeroSubstitutionTerm = String.format("%s", globalMeanForTargetClass);
      } else {
        int targetColumnIndex = getColumnIndexByName(fr, targetColumnName);
        double globalMeanForNonTargetClass = 1 - globalMeanForTargetClass;  // This is probably a bad idea to use frequencies for `0` class when we use frequencies for `1` class elsewhere
        denominatorIsZeroSubstitutionTerm = String.format("ifelse ( == (cols %s [%d]) 1) %f  %f", fr._key, targetColumnIndex, globalMeanForTargetClass, globalMeanForNonTargetClass);
      }
      return denominatorIsZeroSubstitutionTerm;
    }

    Frame addNoise(Frame fr, String applyToColumnName, double noiseLevel, long seed) {
      int appyToColumnIndex = getColumnIndexByName(fr, applyToColumnName);
      if (seed == -1) seed = new Random().nextLong();
      Vec zeroVec = Vec.makeZero(fr.numRows());
      Vec randomVec = zeroVec.makeRand(seed);
      Vec runif = fr.add("runif", randomVec);
      int runifIdx = getColumnIndexByName(fr, "runif");
      new AddNoiseTask(appyToColumnIndex, runifIdx, noiseLevel).doAll(fr);

      fr.remove("runif");
      randomVec.remove();
      zeroVec.remove();
      runif.remove();
      return fr;
    }

    public static class AddNoiseTask extends MRTask<AddNoiseTask> {
      int applyToColumnIdx;
      int runifIdx;
      double noiseLevel;

      public AddNoiseTask(int applyToColumnIdx, int runifIdx, double noiseLevel) {
        this.applyToColumnIdx = applyToColumnIdx;
        this.runifIdx = runifIdx;
        this.noiseLevel = noiseLevel;
      }

      @Override
      public void map(Chunk cs[]) {
        Chunk column = cs[applyToColumnIdx];
        Chunk runifCol = cs[runifIdx];
        for (int i = 0; i < column._len; i++) {
          if (!column.isNA(i)) {
            column.set(i, column.atd(i) + (runifCol.atd(i) * 2 * noiseLevel - noiseLevel));
          }
        }
      }
    }

    Frame subtractTargetValueForLOO(Frame data, String targetColumnName) {
      int numeratorIndex = getColumnIndexByName(data, "numerator");
      int denominatorIndex = getColumnIndexByName(data, "denominator");
      int targetIndex = getColumnIndexByName(data, targetColumnName);

      new SubtractCurrentRowForLeaveOneOutTask(numeratorIndex, denominatorIndex, targetIndex).doAll(data);
      return data;
    }

    public static class SubtractCurrentRowForLeaveOneOutTask extends MRTask<SubtractCurrentRowForLeaveOneOutTask> {
      int numeratorIdx;
      int denominatorIdx;
      int targetIdx;

      public SubtractCurrentRowForLeaveOneOutTask(int numeratorIdx, int denominatorIdx, int targetIdx) {
        this.numeratorIdx = numeratorIdx;
        this.denominatorIdx = denominatorIdx;
        this.targetIdx = targetIdx;
      }

      @Override
      public void map(Chunk cs[]) {
        Chunk num = cs[numeratorIdx];
        Chunk den = cs[denominatorIdx];
        Chunk target = cs[targetIdx];
        for (int i = 0; i < num._len; i++) {
          if (!target.isNA(i)) {
            num.set(i, num.atd(i) - target.atd(i));
            den.set(i, den.atd(i) - 1);
          }
        }
      }
    }

    /**
     * Core method for applying pre-calculated encodings to the dataset. There are multiple overloaded methods that we will
     * probably be able to get rid off if we are not going to expose Java API for TE.
     * We can just stick to one signature that will suit internal representations  of the AutoML's pipeline.
     *
     * @param data dataset that will be used as a base for creation of encodings .
     * @param columnsToEncode set of columns names that we want to encode.
     * @param targetColumnName name of the column with respect to which we were computing encodings.
     * @param columnToEncodingMap map of the prepared encodings with the keys being the names of the columns.
     * @param dataLeakageHandlingStrategy see TargetEncoding.DataLeakageHandlingStrategy //TODO use common interface for stronger type safety.
     * @param foldColumnName numerical column that contains fold number the row is belong to.
     * @param withBlendedAvg whether to apply blending or not.
     * @param noiseLevel amount of noise to add to the final encodings.
     * @param seed we might want to specify particular values for reproducibility in tests.
     * @return
     */
    public Frame applyTargetEncoding(Frame data,
                                     String[] columnsToEncode,
                                     String targetColumnName,
                                     Map<String, Frame> columnToEncodingMap,
                                     byte dataLeakageHandlingStrategy,
                                     String foldColumnName,
                                     boolean withBlendedAvg,
                                     double noiseLevel,
                                     long seed,
                                     boolean isTrainOrValidSet) {

        if(noiseLevel < 0 )
            throw new IllegalStateException("`noiseLevel` must be non-negative");

        //TODO Should we remove string columns from `data` as it is done in R version (see: https://0xdata.atlassian.net/browse/PUBDEV-5266) ?

        Frame dataCopy = data.deepCopy(Key.make().toString());
        DKV.put(dataCopy);

        Frame dataWithAllEncodings = null ;
        if(isTrainOrValidSet) {
          Frame dataWithEncodedTarget = ensureTargetColumnIsNumericOrBinaryCategorical(dataCopy, targetColumnName);
          dataWithAllEncodings = dataWithEncodedTarget.deepCopy(Key.make().toString());
          DKV.put(dataWithAllEncodings);
          dataWithEncodedTarget.delete();
        }
        else {
          dataWithAllEncodings = dataCopy;
        }


        for ( String teColumnName: columnsToEncode) {

            String newEncodedColumnName = teColumnName + "_te";

            Frame dataWithMergedAggregations = null;
            Frame dataWithEncodings = null;
            Frame dataWithEncodingsAndNoise = null;

            Frame targetEncodingMap = columnToEncodingMap.get(teColumnName);

            int teColumnIndex = getColumnIndexByName(dataWithAllEncodings, teColumnName);
            Frame holdoutEncodeMap = null;

            switch (dataLeakageHandlingStrategy) {
                case DataLeakageHandlingStrategy.KFold:
                    assert isTrainOrValidSet : "Following calculations assume we can access target column but we can do this only on training and validation sets.";
                    if(foldColumnName == null)
                        throw new IllegalStateException("`foldColumn` must be provided for dataLeakageHandlingStrategy = KFold");

                    int teColumnIndexInEncodingMap = getColumnIndexByName(targetEncodingMap, teColumnName);

                    int foldColumnIndex = getColumnIndexByName(dataWithAllEncodings, foldColumnName);
                    long[] foldValues = getUniqueValuesOfTheFoldColumn(targetEncodingMap, 1);

                    Scope.enter();

                    // Following part is actually a preparation phase for KFold case. Maybe we should move it to prepareEncodingMap method.
                    try {
                        for (long foldValue : foldValues) {
                            Frame outOfFoldData = getOutOfFoldData(targetEncodingMap, foldColumnName, foldValue);

                            Frame groupedByTEColumnAndAggregate = groupByTEColumnAndAggregate(outOfFoldData, teColumnIndexInEncodingMap);

                            groupedByTEColumnAndAggregate.renameColumn( "sum_numerator", "numerator");
                            groupedByTEColumnAndAggregate.renameColumn( "sum_denominator", "denominator");

                            Frame groupedWithAppendedFoldColumn = groupedByTEColumnAndAggregate.addCon("foldValueForMerge", foldValue);

                            if (holdoutEncodeMap == null) {
                                holdoutEncodeMap = groupedWithAppendedFoldColumn;
                            } else {
                                Frame newHoldoutEncodeMap = rBind(holdoutEncodeMap, groupedWithAppendedFoldColumn);
                                holdoutEncodeMap.delete();
                                holdoutEncodeMap = newHoldoutEncodeMap;
                            }

                            outOfFoldData.delete();
                            Scope.track(groupedByTEColumnAndAggregate);
                        }
                    } finally {
                        Scope.exit();
                    }
                    // End of the preparation phase

                    dataWithMergedAggregations = mergeByTEAndFoldColumns(dataWithAllEncodings, holdoutEncodeMap, teColumnIndex, foldColumnIndex, teColumnIndexInEncodingMap);

                    dataWithEncodings = calculateEncoding(dataWithMergedAggregations, targetEncodingMap, targetColumnName, newEncodedColumnName, withBlendedAvg);

                    dataWithEncodingsAndNoise = applyNoise(dataWithEncodings, newEncodedColumnName, noiseLevel, seed);

                    // Cases when we can introduce NA's:
                    // 1) if column is represented only in one fold then during computation of out-of-fold subsets we will get empty aggregations.
                    //   When merging with the original dataset we will get NA'a on the right side
                    // Note: since we create encoding based on training dataset and use KFold mainly when we apply encoding to the training set,
                    // there is zero probability that we haven't seen some category.
                    imputeWithMean(dataWithEncodingsAndNoise, getColumnIndexByName(dataWithEncodingsAndNoise, newEncodedColumnName));

                    removeNumeratorAndDenominatorColumns(dataWithEncodingsAndNoise);

                    dataWithAllEncodings.delete();
                    dataWithAllEncodings = dataWithEncodingsAndNoise.deepCopy(Key.make().toString());
                    DKV.put(dataWithAllEncodings);

                    dataWithEncodingsAndNoise.delete();
                    holdoutEncodeMap.delete();

                    break;
                case DataLeakageHandlingStrategy.LeaveOneOut:
                    assert isTrainOrValidSet : "Following calculations assume we can access target column but we can do this only on training and validation sets.";
                    foldColumnIsInEncodingMapCheck(foldColumnName, targetEncodingMap);

                    Frame groupedTargetEncodingMap = groupingIgnoringFordColumn(foldColumnName, targetEncodingMap, teColumnName);

                    int teColumnIndexInGroupedEncodingMap = getColumnIndexByName(groupedTargetEncodingMap, teColumnName);
                    dataWithMergedAggregations = mergeByTEColumn(dataWithAllEncodings, groupedTargetEncodingMap, teColumnIndex, teColumnIndexInGroupedEncodingMap);

                    Frame preparedFrame = subtractTargetValueForLOO(dataWithMergedAggregations,  targetColumnName);

                    dataWithEncodings = calculateEncoding(preparedFrame, groupedTargetEncodingMap, targetColumnName, newEncodedColumnName, withBlendedAvg); // do we really need to pass groupedTargetEncodingMap again?

                    dataWithEncodingsAndNoise = applyNoise(dataWithEncodings, newEncodedColumnName, noiseLevel, seed);

                    // Cases when we can introduce NA's:
                    // 1) Only in case when our encoding map has not seen some category.
                    imputeWithMean(dataWithEncodingsAndNoise, getColumnIndexByName(dataWithEncodingsAndNoise, newEncodedColumnName));

                    removeNumeratorAndDenominatorColumns(dataWithEncodingsAndNoise);

                    dataWithAllEncodings.delete();
                    dataWithAllEncodings = dataWithEncodingsAndNoise.deepCopy(Key.make().toString());
                    DKV.put(dataWithAllEncodings);

                    preparedFrame.delete();
                    dataWithEncodingsAndNoise.delete();
                    groupedTargetEncodingMap.delete();

                    break;
                case DataLeakageHandlingStrategy.None:
                    foldColumnIsInEncodingMapCheck(foldColumnName, targetEncodingMap);
                    Frame groupedTargetEncodingMapForNone = groupingIgnoringFordColumn(foldColumnName, targetEncodingMap, teColumnName);
                    int teColumnIndexInGroupedEncodingMapNone = getColumnIndexByName(groupedTargetEncodingMapForNone, teColumnName);
                    dataWithMergedAggregations = mergeByTEColumn(dataWithAllEncodings, groupedTargetEncodingMapForNone, teColumnIndex, teColumnIndexInGroupedEncodingMapNone);

                    if(isTrainOrValidSet)
                      dataWithEncodings = calculateEncoding(dataWithMergedAggregations, groupedTargetEncodingMapForNone, targetColumnName, newEncodedColumnName, withBlendedAvg);
                    else
                      dataWithEncodings = calculateEncoding(dataWithMergedAggregations, groupedTargetEncodingMapForNone, null, newEncodedColumnName, withBlendedAvg);

                    // In cases when encoding has not seen some levels we will impute NAs with mean computed from training set. Mean is a dataleakage btw.
                    // Note: In case of creating encoding map based on the holdout set we'd better use stratified sampling.
                    // Maybe even choose size of holdout taking into account size of the minimal set that represents all levels.
                    // Otherwise there are higher chances to get NA's for unseen categories.
                    imputeWithMean(dataWithEncodings, getColumnIndexByName(dataWithEncodings, newEncodedColumnName));

                    dataWithEncodingsAndNoise = applyNoise(dataWithEncodings, newEncodedColumnName, noiseLevel, seed);

                    removeNumeratorAndDenominatorColumns(dataWithEncodingsAndNoise);

                    dataWithAllEncodings.delete();
                    dataWithAllEncodings = dataWithEncodingsAndNoise.deepCopy(Key.make().toString());
                    DKV.put(dataWithAllEncodings);

                    dataWithEncodingsAndNoise.delete();
                    groupedTargetEncodingMapForNone.delete();
            }

            dataWithMergedAggregations.delete();
            dataWithEncodings.delete();
        }

        dataCopy.delete();

        return dataWithAllEncodings;
    }

    private Frame calculateEncoding(Frame preparedFrame, Frame encodingMap, String targetColumnName, String newEncodedColumnName, boolean withBlendedAvg) {
        if (withBlendedAvg) {
            return calculateAndAppendBlendedTEEncoding(preparedFrame, encodingMap, targetColumnName, newEncodedColumnName);

        } else {
            return calculateAndAppendTEEncoding(preparedFrame, encodingMap, targetColumnName, newEncodedColumnName);
        }
    }

    private Frame applyNoise(Frame frameWithEncodings, String newEncodedColumnName, double noiseLevel, long seed) {
        if(noiseLevel > 0) {
            return addNoise(frameWithEncodings, newEncodedColumnName, noiseLevel, seed);
        } else {
            return frameWithEncodings;
        }
    }

    void removeNumeratorAndDenominatorColumns(Frame fr) {
        Vec removedNumeratorNone = fr.remove("numerator");
        removedNumeratorNone.remove();
        Vec removedDenominatorNone = fr.remove("denominator");
        removedDenominatorNone.remove();
    }

    private void foldColumnIsInEncodingMapCheck(String foldColumnName, Frame targetEncodingMap) {
        if(foldColumnName == null && targetEncodingMap.names().length > 3) {
            throw new IllegalStateException("Passed along encoding map possibly contains fold column. Please provide fold column name so that it becomes possible to regroup (by ignoring folds).");
        }
    }

    Frame groupingIgnoringFordColumn(String foldColumnName, Frame targetEncodingMap, String teColumnName) {
        if (foldColumnName != null) {
            int teColumnIndex = getColumnIndexByName(targetEncodingMap, teColumnName);

            Frame newTargetEncodingMap = groupByTEColumnAndAggregate(targetEncodingMap, teColumnIndex);
            newTargetEncodingMap.renameColumn( "sum_numerator", "numerator");
            newTargetEncodingMap.renameColumn( "sum_denominator", "denominator");
            return newTargetEncodingMap;
        } else {
            Frame targetEncodingMapCopy = targetEncodingMap.deepCopy(Key.make().toString());
            DKV.put(targetEncodingMapCopy);
            return targetEncodingMapCopy;
        }
    }

    public Frame applyTargetEncoding(Frame data,
                                     String[] columnsToEncode,
                                     String targetColumnName,
                                     Map<String, Frame> targetEncodingMap,
                                     byte dataLeakageHandlingStrategy,
                                     String foldColumn,
                                     boolean withBlendedAvg,
                                     long seed,
                                     boolean isTrainOrValidSet) {
        double defaultNoiseLevel = 0.01;
        double noiseLevel = 0.0;
        int targetIndex = getColumnIndexByName(data, targetColumnName);
        Vec targetVec = data.vec(targetIndex);
        if(targetVec.isNumeric()) {
            noiseLevel = defaultNoiseLevel * (targetVec.max() - targetVec.min());
        } else {
            noiseLevel = defaultNoiseLevel;
        }
        return this.applyTargetEncoding(data, columnsToEncode, targetColumnName, targetEncodingMap, dataLeakageHandlingStrategy, foldColumn, withBlendedAvg, noiseLevel, seed, isTrainOrValidSet);
    }

    public Frame applyTargetEncoding(Frame data,
                                     String[] columnsToEncode,
                                     String targetColumnName,
                                     Map<String, Frame> targetEncodingMap,
                                     byte dataLeakageHandlingStrategy,
                                     boolean withBlendedAvg,
                                     long seed,
                                     boolean isTrainOrValidSet) {
        return applyTargetEncoding(data, columnsToEncode, targetColumnName, targetEncodingMap, dataLeakageHandlingStrategy, null, withBlendedAvg, seed, isTrainOrValidSet);
    }

    public Frame applyTargetEncoding(Frame data,
                                     int[] columnIndexesToEncode,
                                     int targetIndex,
                                     Map<String, Frame> targetEncodingMap,
                                     byte dataLeakageHandlingStrategy,
                                     int foldColumnIndex,
                                     boolean withBlendedAvg,
                                     long seed,
                                     boolean isTrainOrValidSet) {
        String[] columnNamesToEncode = getColumnNamesBy(data, columnIndexesToEncode);
        String targetColumnName = getColumnNameBy(data, targetIndex);
        String foldColumnName = getColumnNameBy(data, foldColumnIndex);
        return this.applyTargetEncoding(data, columnNamesToEncode, targetColumnName, targetEncodingMap, dataLeakageHandlingStrategy, foldColumnName, withBlendedAvg, seed, isTrainOrValidSet);
    }

    public Frame applyTargetEncoding(Frame data,
                                     int[] columnIndexesToEncode,
                                     int targetIndex,
                                     Map<String, Frame> targetEncodingMap,
                                     byte dataLeakageHandlingStrategy,
                                     int foldColumnIndex,
                                     boolean withBlendedAvg,
                                     double noiseLevel,
                                     long seed,
                                     boolean isTrainOrValidSet) {
        String[] columnNamesToEncode = getColumnNamesBy(data, columnIndexesToEncode);
        String targetColumnName = getColumnNameBy(data, targetIndex);
        String foldColumnName = getColumnNameBy(data, foldColumnIndex);
        return this.applyTargetEncoding(data, columnNamesToEncode, targetColumnName, targetEncodingMap, dataLeakageHandlingStrategy, foldColumnName, withBlendedAvg, noiseLevel, seed, isTrainOrValidSet);
    }

    public Frame applyTargetEncoding(Frame data,
                                     int[] columnIndexesToEncode,
                                     int targetColumnIndex,
                                     Map<String, Frame> targetEncodingMap,
                                     byte dataLeakageHandlingStrategy,
                                     boolean withBlendedAvg,
                                     double noiseLevel,
                                     long seed,
                                     boolean isTrainOrValidSet) {
        String[] columnNamesToEncode = getColumnNamesBy(data, columnIndexesToEncode);
        String targetColumnName = getColumnNameBy(data, targetColumnIndex);
        return applyTargetEncoding(data, columnNamesToEncode, targetColumnName, targetEncodingMap, dataLeakageHandlingStrategy, withBlendedAvg, noiseLevel, seed, isTrainOrValidSet);
    }

    public Frame applyTargetEncoding(Frame data,
                                     String[] columnNamesToEncode,
                                     String targetColumnName,
                                     Map<String, Frame> targetEncodingMap,
                                     byte dataLeakageHandlingStrategy,
                                     boolean withBlendedAvg,
                                     double noiseLevel,
                                     long seed,
                                     boolean isTrainOrValidSet) {
        assert dataLeakageHandlingStrategy != DataLeakageHandlingStrategy.KFold : "Use another overloaded method for KFold dataLeakageHandlingStrategy.";
        return applyTargetEncoding(data, columnNamesToEncode, targetColumnName, targetEncodingMap, dataLeakageHandlingStrategy, null, withBlendedAvg, noiseLevel, seed, isTrainOrValidSet);
    }

    //TODO usefull during development remove
    public void checkNumRows(Frame before, Frame after) {
        long droppedCount = before.numRows()- after.numRows();
        if(droppedCount != 0) {
            Log.warn(String.format("Number of rows has dropped by %d after manipulations with frame ( %s , %s ).", droppedCount, before._key, after._key));
        }
    }

    // TODO usefull for development. remove.
    private void printOutFrameAsTable(Frame fr) {
        TwoDimTable twoDimTable = fr.toTwoDimTable();
        System.out.println(twoDimTable.toString());
    }

    // TODO usefull for development. remove.
    private void printOutFrameAsTable(Frame fr, boolean full, boolean rollups) {
        TwoDimTable twoDimTable = fr.toTwoDimTable(0, 1000000, rollups);
        System.out.println(twoDimTable.toString(2, full));
    }

    // TODO usefull for development. remove.
    private void printOutColumnsMeta(Frame fr) {
        for (String header : fr.toTwoDimTable().getColHeaders()) {
            String type = fr.vec(header).get_type_str();
            int cardinality = fr.vec(header).cardinality();
            System.out.println(header + " - " + type + String.format("; Cardinality = %d", cardinality));
        }
    }
}