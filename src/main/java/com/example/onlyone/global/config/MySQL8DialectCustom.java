package com.example.onlyone.global.config;

import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.dialect.MySQL8Dialect;
import org.hibernate.type.StandardBasicTypes;

public class MySQL8DialectCustom extends MySQL8Dialect {
    @Override
    public void initializeFunctionRegistry(FunctionContributions functionContributions) {
        super.initializeFunctionRegistry(functionContributions);
        
        // MATCH AGAINST 함수 등록
        functionContributions.getFunctionRegistry().registerPattern(
                "match",
                "match(?1, ?2) against(?3 in natural language mode)",
                functionContributions.getTypeConfiguration().getBasicTypeRegistry().resolve(StandardBasicTypes.DOUBLE)
        );
    }
}