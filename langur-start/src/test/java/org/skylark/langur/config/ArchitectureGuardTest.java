package org.skylark.langur.config;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * H11 依赖治理 - ArchUnit 架构守卫（守护 P1/P2 分层契约）。
 * <p>四条不变量：① <b>domain 纯度（P1）</b>——domain 不得依赖 Spring / infrastructure（守护 {@code DecisionPort}
 * 及 R0/R2/R4/R5 纯领域组件的零外部依赖，v3.0 关联点）；② <b>api 不直调 domain（P2）</b>——api 层只经
 * application 访问领域（api → application → domain）；③ <b>common 无状态 Bean</b>——common 仅共享契约
 * （SPI 接口/纯值对象），不得有 Spring 容器 Bean；④ <b>分层无环</b>——六模块依赖须为 DAG（无循环依赖）。</p>
 */
class ArchitectureGuardTest {

    private final JavaClasses classes = new ClassFileImporter().importPackages("org.skylark.langur");

    @Test
    void domainShouldNotDependOnSpringOrInfrastructure() {
        noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "..infrastructure..")
                .check(classes);
    }

    @Test
    void apiShouldNotDependOnDomainDirectly() {
        noClasses().that().resideInAPackage("..api..")
                .should().dependOnClassesThat().resideInAPackage("..domain..")
                .check(classes);
    }

    @Test
    void commonShouldHaveNoStatefulSpringBeans() {
        noClasses().that().resideInAPackage("..common..")
                .should().beAnnotatedWith("org.springframework.stereotype.Component")
                .orShould().beAnnotatedWith("org.springframework.stereotype.Service")
                .orShould().beAnnotatedWith("org.springframework.stereotype.Repository")
                .check(classes);
    }

    @Test
    void moduleSlicesShouldBeFreeOfCycles() {
        slices().matching("org.skylark.langur.(*)..").should().beFreeOfCycles().check(classes);
    }
}
