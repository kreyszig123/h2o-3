setwd(normalizePath(dirname(R.utils::commandArgs(asValues=TRUE)$"f")))
source("../../../scripts/h2o-r-test-setup.R")

test.model.targetencoder <- function() {
    test.model.targetencoder.basic <- function() {
        data <- h2o.importFile(path = locate('smalldata/gbm_test/titanic.csv'), col.types = list(by.col.name = c("survived"), types = c("factor")))
        encoded_columns <- c('home.dest', 'cabin', 'embarked')
        target_encoder <- h2o.targetencoder(training_frame = data, x = encoded_columns, y = "survived")
        encoded_data <- h2o.transform(target_encoder, data) # For now, there is only the predict method
        expect_false(is.null(encoded_data))
        expect_equal(h2o.ncol(data) + length(encoded_columns), h2o.ncol(encoded_data))
        expect_true(h2o.nrow(data) == 1309)

        # Test fold_column proper handling + kfold data leakage strategy defined
        target_encoder <- h2o.targetencoder(training_frame = data, x = encoded_columns, y = "survived",
        fold_column = "pclass", data_leakage_handling = "KFold")
        encoded_data <- h2o.transform(target_encoder, data) # For now, there is only the predict method
        expect_false(is.null(encoded_data))
        encoded_data_predict <- h2o.predict(target_encoder, data)
        expect_equal(encoded_data, encoded_data_predict)

        mojo_name <- h2o.download_mojo(model = target_encoder, path = tempdir())
        mojo_path <- paste0(tempdir(), "/", mojo_name)
        expect_true(file.exists(mojo_path))

        # No encoded columns (x) given
        target_encoder <- h2o.targetencoder(training_frame = data, y = "survived")
        encoded_data <- h2o.transform(target_encoder, data) # For now, there is only the predict method

        expected_columns <-
        c(
        "home.dest", "embarked", "cabin", "sex", "pclass", "survived", "name", "age", "sibsp", "parch",
        "ticket", "fare", "boat", "body", "sex_te", "cabin_te", "embarked_te", "home.dest_te" # 4 new encoded columns
        )
        actual_columns <- h2o.colnames(encoded_data)
        print(actual_columns)
        expect_true(all(sort(actual_columns) == sort(expected_columns)))
    }

    test.that.noise.could.be.specified.during.transformation <- function() {

        # With deprecated API
        data <- h2o.importFile(path = locate('smalldata/gbm_test/titanic.csv'), col.types=list(by.col.name=c("survived"),types=c("factor")))
        te_cols <- list("embarked")

        data$fold <- h2o.kfold_column(data, nfolds = 5, seed = 1234)

        encoding_map <- h2o.target_encode_fit(data, te_cols, "survived", fold_column = "fold")

        # transform training data with `kfold` and `noise`
        encoded_train_with_target_encode_transform <- h2o.target_encode_transform(data, te_cols, "survived", encoding_map, blended_avg=FALSE, holdout_type = "kfold", fold_column = "fold", noise = 0.01, seed = 1234)

        # With current API
        data2 <- h2o.importFile(path = locate('smalldata/gbm_test/titanic.csv'), col.types=list(by.col.name=c("survived"),types=c("factor")))
        data2$fold <- h2o.kfold_column(data2, nfolds = 5, seed = 1234)

        encoded_columns <- c('embarked')
        target_encoder <- h2o.targetencoder(training_frame = data2, x = encoded_columns, y = "survived", fold_column = "fold", data_leakage_handling = "KFold")
        encoded_train_with_transform <- h2o.transform(target_encoder, data2, noise=0.01, seed = 1234) # default value for seed in new API was 0 (ie default value for on backend for variable of type long)

        compareFrames(encoded_train_with_target_encode_transform, encoded_train_with_transform)
    }

    makeSuite(
        test.model.targetencoder.basic,
        test.that.noise.could.be.specified.during.transformation
    )
}

doSuite("Target Encoder Model test", test.model.targetencoder(), time_monitor=TRUE)
