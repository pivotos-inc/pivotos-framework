package com.pivotos.starter.cloud.api.support.fixture;

/**
 * 反射定位语义测试用的最小 Facade：同名同元数方法刻意留出重载，
 * 用于钉死「按元数收敛」的行为（不按形参类型精确匹配）。
 */
public interface ProbeFacade {

    String greet(String name);

    String greet(String name, String suffix);

    String ping();
}
