package dev.marshalhq.core.replay;

import dev.marshalhq.core.*;
import dev.marshalhq.core.rules.*;

import java.time.Instant;
import java.util.List;

public class ReplayTestHelper {

    public static RuleEngine defaultEngine() {
        return new RuleEngine(List.of(
            new MissingSignatureRule(),
            new SignatureDroppedRule(),
            new MajorVersionJumpRule(),
            new NewMaintainerRule(),
            new DependencyExplosionRule(),
            new RepoUrlChangedRule(),
            new YankedVersionRule()
        ));
    }

    public static VersionMetadata safeVersion(Coordinates coords) {
        return new VersionMetadata(coords, "maintainer@example.com", "AAABBBCCC",
            true, List.of(), 5, "https://github.com/example/lib",
            Instant.now(), false);
    }

    public static VersionMetadata safeVersion(Coordinates coords, String email, int depCount, String repoUrl) {
        return new VersionMetadata(coords, email, "AAABBBCCC",
            true, List.of(), depCount, repoUrl, Instant.now(), false);
    }
}
