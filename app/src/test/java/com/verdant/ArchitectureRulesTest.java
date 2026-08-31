package com.verdant;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.core.importer.ImportOption;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

@AnalyzeClasses(packages = "com.verdant", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {

    @ArchTest
    static final ArchRule money_never_uses_floating_point =
        noFields().that().areDeclaredInClassesThat().resideInAPackage("..domain..")
            .should().haveRawType(double.class)
            .orShould().haveRawType(float.class)
            .orShould().haveRawType(Double.class)
            .orShould().haveRawType(Float.class)
            .because("floating point cannot represent decimal money exactly");

    @ArchTest
    static final ArchRule web_never_touches_repositories =
        noClasses().that().resideInAPackage("..web..")
            .should().accessClassesThat().resideInAPackage("..repository..")
            .allowEmptyShould(true);
}
