package uskoag.wallet.daemon;

import uskoag.wallet.wire.Health;

/**
 * What one probe came back with, keeping two things apart that the first version conflated.
 *
 * @param reached true when Google answered at all. False means the question did not get through, which is
 *                a fact about the network and not about the credential.
 * @param verdict a fact about the credential — a dead token, a withdrawn scope, a refused API — or null
 *                when the probe passed or never got an answer. Only a non-null verdict may set the
 *                credential's state.
 * @param note    the words a person reads, whichever of the two it was
 */
public record ProbeOutcome(boolean reached, Health verdict, String note) {
}
