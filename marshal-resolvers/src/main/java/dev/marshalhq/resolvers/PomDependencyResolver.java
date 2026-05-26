package dev.marshalhq.resolvers;

import dev.marshalhq.core.Coordinates;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class PomDependencyResolver {
    private static final Logger log = LoggerFactory.getLogger(PomDependencyResolver.class);
    private final RepositorySystem system;
    private final RepositorySystemSession session;
    private final List<RemoteRepository> repos;

    public PomDependencyResolver() {
        this.system = new RepositorySystemSupplier().get();
        this.session = newSession(system);
        this.repos = List.of(
            new RemoteRepository.Builder("central", "default", "https://repo1.maven.org/maven2/").build()
        );
    }

    public List<Coordinates> resolve(Path pomPath) {
        try {
            org.apache.maven.model.Model model = parsePom(pomPath);
            if (model == null) return List.of();

            List<Coordinates> result = new ArrayList<>();
            for (org.apache.maven.model.Dependency dep : model.getDependencies()) {
                String version = dep.getVersion();
                if (version == null || version.startsWith("$")) continue;
                result.add(new Coordinates(dep.getGroupId(), dep.getArtifactId(), version));
            }
            return result;
        } catch (Exception e) {
            log.error("Failed to resolve dependencies from {}: {}", pomPath, e.getMessage());
            return List.of();
        }
    }

    private org.apache.maven.model.Model parsePom(Path pomPath) {
        try {
            org.apache.maven.model.io.xpp3.MavenXpp3Reader reader = new org.apache.maven.model.io.xpp3.MavenXpp3Reader();
            try (java.io.FileReader fr = new java.io.FileReader(pomPath.toFile())) {
                return reader.read(fr);
            }
        } catch (Exception e) {
            log.error("Failed to parse POM {}: {}", pomPath, e.getMessage());
            return null;
        }
    }

    private static RepositorySystemSession newSession(RepositorySystem system) {
        var session = MavenRepositorySystemUtils.newSession();
        LocalRepository localRepo = new LocalRepository(
            System.getProperty("user.home") + "/.m2/repository"
        );
        session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, localRepo));
        return session;
    }
}
