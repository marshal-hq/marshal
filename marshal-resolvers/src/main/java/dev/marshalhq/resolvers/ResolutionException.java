package dev.marshalhq.resolvers;

/**
 * Thrown when a resolver could not analyze a project at all — the build failed,
 * timed out, or no build tool was found. This is distinct from a successful
 * resolution that yields zero dependencies. Callers must surface it as "could not
 * analyze" (a non-zero, non-clean outcome), never as "nothing to flag" (S06): a
 * security tool reporting all-clear on a build it choked on is a false negative.
 */
public class ResolutionException extends RuntimeException {

    public ResolutionException(String message) {
        super(message);
    }
}
