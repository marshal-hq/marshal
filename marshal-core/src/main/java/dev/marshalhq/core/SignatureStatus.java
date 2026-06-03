package dev.marshalhq.core;

public enum SignatureStatus {
    PRESENT,  // signature fetch succeeded and signature file exists
    ABSENT,   // signature fetch succeeded and signature file does not exist (404)
    UNKNOWN   // signature fetch failed (timeout, 429 exhausted, network error)
}
