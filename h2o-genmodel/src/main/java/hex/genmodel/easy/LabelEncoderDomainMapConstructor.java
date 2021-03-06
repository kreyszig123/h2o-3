package hex.genmodel.easy;

import hex.genmodel.GenModel;

import java.util.HashMap;
import java.util.Map;

public class LabelEncoderDomainMapConstructor extends DomainMapConstructor {
  
  public LabelEncoderDomainMapConstructor(GenModel m, Map<String, Integer> columnNameToIndex) {
    super(m, columnNameToIndex);
  }

  @Override
  public Map<Integer, CategoricalEncoder> create() {
    Map<Integer, CategoricalEncoder> domainMap = new HashMap<>();
    String[] columnNames = _m.getNames();
    for (int i = 0; i < _m.getNumCols(); i++) {
      String colName = i < _m.getNumCols() ? columnNames[i] : _m._responseColumn;
      Integer colIndex = _columnNameToIndex.get(colName);
      String[] domainValues = _m.getOrigDomainValues()[i];
      if (domainValues != null) {
        domainMap.put(colIndex, new LabelEncoder(colIndex, domainValues));
      }
    }
    return domainMap;
  }
}
