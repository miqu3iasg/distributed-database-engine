package dev.geodb.arch;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Determinism rules for core modules.
 * <p>
 * Core modules ({@code raft}, {@code range}, {@code txn}, {@code mvcc}, {@code cluster}) must
 * reach time, randomness, network, disk and threads only through {@code geodb-env}.
 * See {@code AGENTS.md} section 5 and ADR-003 / ADR-004.
 */
@AnalyzeClasses(packages = "dev.geodb")
class DeterminismRules {

    private static final String[] CORE_PACKAGES = {
        "dev.geodb.raft..",
        "dev.geodb.range..",
        "dev.geodb.txn..",
        "dev.geodb.mvcc..",
        "dev.geodb.cluster.."
    };

    private static final DescribedPredicate<JavaClass> IN_CORE_PACKAGE = new DescribedPredicate<JavaClass>(
        "in a core package (" + String.join(", ", CORE_PACKAGES) + ")"
    ) {
        @Override
        public boolean test(JavaClass input) {
            String pkg = input.getPackageName();
            for (String core : CORE_PACKAGES) {
                String regex = core.replace("..", ".*");
                if (pkg.matches(regex)) {
                    return true;
                }
            }
            return false;
        }
    };

    private static final DescribedPredicate<JavaClass> FORBIDDEN_CLASSES = new DescribedPredicate<JavaClass>(
        "forbidden class in core module"
    ) {
        @Override
        public boolean test(JavaClass input) {
            String name = input.getName();
            return name.equals("java.util.Random")
                || name.equals("java.util.concurrent.ThreadLocalRandom")
                || name.equals("java.util.UUID")
                || name.equals("java.lang.Thread")
                || name.startsWith("java.nio.file.");
        }
    };

    @ArchTest
    static final ArchRule no_direct_nio_file_access = noClasses()
        .that(IN_CORE_PACKAGE)
        .should()
        .dependOnClassesThat()
        .resideInAPackage("java.nio.file..")
        .allowEmptyShould(true);

    @ArchTest
    static final ArchRule no_forbidden_api_in_core = noClasses()
        .that(IN_CORE_PACKAGE)
        .should()
        .dependOnClassesThat(FORBIDDEN_CLASSES)
        .allowEmptyShould(true);
}
