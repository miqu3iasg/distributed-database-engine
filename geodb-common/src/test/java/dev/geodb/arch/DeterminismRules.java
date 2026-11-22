package dev.geodb.arch;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Determinism rules for core modules.
 *
 * <p>Core modules ({@code raft}, {@code range}, {@code txn}, {@code mvcc}, {@code cluster}) must
 * reach time, randomness, network, disk and threads only through {@code geodb-env}.
 * See {@code AGENTS.md} section 5 and ADR-003 / ADR-004.
 *
 * @author Miquéias Alves Medeiros
 * @version 1.0
 * @see <a href="AGENTS.md#5-determinism-rules-enforced-by-archunit-forbidden-apis">AGENTS.md section 5</a>
 * @see ADR-003
 * @see ADR-004
 * @since 1.0
 */
@AnalyzeClasses(packages = "dev.geodb")
class DeterminismRules
{

    /**
     * Packages considered core for the determinism rules.
     *
     * <p>These are the modules that must not touch time, randomness, threads, or disk
     * directly; they must use the {@code geodb-env} SPI instead.
     */
    private static final String[] CORE_PACKAGES = {
        "dev.geodb.raft..",
        "dev.geodb.range..",
        "dev.geodb.txn..",
        "dev.geodb.mvcc..",
        "dev.geodb.cluster.."
    };

    /**
     * Predicate matching any class residing in a core package.
     *
     * <p>Used by every ArchUnit rule to scope the check to core modules only.
     */
    private static final DescribedPredicate<JavaClass> IN_CORE_PACKAGE = new DescribedPredicate<JavaClass>(
        "in a core package (" + String.join(", ", CORE_PACKAGES) + ")"
    ) {
        @Override
        public boolean test(JavaClass input)
        {
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

    /**
     * Predicate matching any forbidden API class in core modules.
     *
     * <p>Forbidden classes are {@code java.util.Random},
     * {@code java.util.concurrent.ThreadLocalRandom}, {@code java.util.UUID},
     * {@code java.lang.Thread}, {@code java.lang.System}, and anything under
     * {@code java.nio.file}.
     */
    private static final DescribedPredicate<JavaClass> FORBIDDEN_CLASSES = new DescribedPredicate<JavaClass>(
        "forbidden class in core module"
    ) {
        @Override
        public boolean test(JavaClass input)
        {
            String name = input.getName();
            return name.equals("java.util.Random")
                || name.equals("java.util.concurrent.ThreadLocalRandom")
                || name.equals("java.util.UUID")
                || name.equals("java.lang.Thread")
                || name.equals("java.lang.System")
                || name.startsWith("java.nio.file.");
        }
    };

    /**
     * Rule forbidding direct {@code java.nio.file.*} access in core modules.
     *
     * <p>Core modules must reach the disk only through the {@code Disk} SPI in
     * {@code geodb-env}.
     */
    @ArchTest
    static final ArchRule no_direct_nio_file_access = noClasses()
        .that(IN_CORE_PACKAGE)
        .should()
        .dependOnClassesThat()
        .resideInAPackage("java.nio.file..")
        .allowEmptyShould(true);

    /**
     * Rule forbidding forbidden APIs in core modules.
     *
     * <p>Forbidden APIs include {@code java.util.Random},
     * {@code java.util.concurrent.ThreadLocalRandom}, {@code java.util.UUID},
     * {@code java.lang.Thread}, {@code java.lang.System} (which exposes
     * {@code currentTimeMillis()} and {@code nanoTime()}), and
     * {@code java.nio.file.*}. Core modules must reach time, randomness,
     * threads, and disk only through the {@code geodb-env} SPI.
     */
    @ArchTest
    static final ArchRule no_forbidden_api_in_core = noClasses()
        .that(IN_CORE_PACKAGE)
        .should()
        .dependOnClassesThat(FORBIDDEN_CLASSES)
        .allowEmptyShould(true);
}
