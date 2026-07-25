package com.pivotos.common.core;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * A4：commons 契约层禁止依赖 Spring 及任何 Web/ORM 框架
 */
class CommonsArchitectureTest {

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.pivotos.common");

    @Test
    void commonsShouldNotDependOnSpring() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework..");
        rule.check(classes);
    }

    @Test
    void commonsShouldNotDependOnWebOrOrmFrameworks() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "jakarta.servlet..",
                        "com.baomidou..",
                        "org.redisson..",
                        "cn.dev33..");
        rule.check(classes);
    }
}
