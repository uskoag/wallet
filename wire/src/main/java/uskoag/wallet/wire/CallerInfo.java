package uskoag.wallet.wire;

/**
 * Where the command was run and what the command was.
 *
 * <p>His ruling on why this exists, and it is the right one: <i>"running in which directory — so I know
 * which project, this single thing makes approval easy"</i>. An approval dialog naming an api, a tier and a
 * document id is a dialog you approve by reflex; one naming the directory you are working in is a question
 * you can actually answer, because you know what you started.
 *
 * <p>The command matters for the other half of the same problem. A batch is approved on the understanding
 * that speed is the point — but a batch you cannot see is a batch that gets treated with suspicion it may
 * not deserve, and the suspicion costs a dialog per item. Shown the command, the person can tell in a
 * second whether what is planned is what they asked for.
 *
 * <p><b>Never a security input.</b> Both fields are supplied by the caller and neither can be verified —
 * the wallet has no way to tell a truthful client from a lying one, which is the recorded reason per-tool
 * contracts were abandoned. So nothing may branch on {@code declared} except what is displayed. An
 * undeclared caller is reported, not restricted: restricting it would apply friction to honest tools only,
 * while anything with an interest in a wide grant would simply declare something plausible.
 *
 * @param workingDir  the caller's directory, or null when it could not be established
 * @param commandLine the command with its arguments, secrets already replaced, or null
 * @param declared    true when a tool stated these; false when they were scraped or are missing
 */
public record CallerInfo(String workingDir, String commandLine, boolean declared) {

    public static final CallerInfo UNKNOWN = new CallerInfo(null, null, false);

    /** Never null, so no reader has to guard: an old client sends no object at all. */
    public static CallerInfo orUnknown(CallerInfo c) {
        return c == null ? UNKNOWN : c;
    }

    /** For the audit's one-line peer column and for any log line. */
    public String describe() {
        var cmd = commandLine == null ? "(command not stated)" : commandLine;
        return workingDir == null ? cmd : cmd + "  [" + workingDir + "]";
    }
}
