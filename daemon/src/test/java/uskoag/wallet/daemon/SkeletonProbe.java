package uskoag.wallet.daemon;

import java.nio.charset.StandardCharsets;

/**
 * The two properties a recorded skeleton has to have, checked against every body shape the rules know.
 *
 * <p><b>It classifies identically.</b> Otherwise the recording is not evidence of what happened and
 * {@code requests --replay} is comparing against something that was never true.
 *
 * <p><b>It carries no document content.</b> Otherwise a policy layer that promises not to retain what
 * passes through it is retaining it in the debug log, which is worse than not having one.
 */
public final class SkeletonProbe {

    /** Distinctive strings planted as cell values and names; none may survive into a skeleton. */
    private static final String[] CONTENT = {
            "SECRET-CELL-VALUE", "Gujarat Habeas", "counterparty@example.com", "42424242"};

    private static int failures;

    public static void main(String[] args) {
        var bodies = new String[]{
                "{\"requests\":[{\"addSheet\":{\"properties\":{\"title\":\"SECRET-CELL-VALUE\"}}}]}",
                "{\"requests\":[{\"addSheet\":{}},{\"updateCells\":{\"rows\":[{\"values\":"
                        + "[{\"userEnteredValue\":{\"stringValue\":\"SECRET-CELL-VALUE\"}}]}]}},"
                        + "{\"deleteSheet\":{\"sheetId\":7}}]}",
                "{\"data\":[{\"range\":\"Sheet1!A1:C10\",\"values\":[[\"SECRET-CELL-VALUE\",\"42424242\"]]},"
                        + "{\"range\":\"Sheet2!A1:D9\",\"values\":[[\"Gujarat Habeas\"]]},"
                        + "{\"range\":\"Notes!A1:B5\",\"values\":[[\"counterparty@example.com\"]]}],"
                        + "\"valueInputOption\":\"RAW\"}",
                "{\"ranges\":[\"Sheet1!A1:C10\",\"Sheet2!A:D\",\"Notes!A1:B5\"]}",
                "{\"ranges\":[\"Sheet1!A1:C10\",\"Notes!A1:B5\"]}",
                "{\"trashed\":true,\"name\":\"Gujarat Habeas\"}",
                "{\"name\":\"SECRET-CELL-VALUE\",\"mimeType\":\"text/plain\"}",
                "{\"ids\":[\"1\",\"2\",\"3\",\"4\",\"5\"]}",
        };
        var paths = new String[]{
                "/v4/spreadsheets/SS1:batchUpdate",
                "/v4/spreadsheets/SS1:batchUpdate",
                "/v4/spreadsheets/SS1/values:batchUpdate",
                "/v4/spreadsheets/SS1/values:batchClear",
                "/v4/spreadsheets/SS1/values:batchClear",
                "/v3/files/FID",
                "/v3/files",
                "/v1/users/me/messages/batchDelete",
        };
        var apis = new String[]{"sheets", "sheets", "sheets", "sheets", "sheets",
                                "drive", "drive", "gmail"};
        var methods = new String[]{"POST", "POST", "POST", "POST", "POST", "PATCH", "POST", "POST"};

        for (var i = 0; i < bodies.length; i++) {
            var raw = bodies[i].getBytes(StandardCharsets.UTF_8);
            var skeleton = Skeleton.of(raw);
            var was = Rules.classify(new RequestFacts(apis[i], methods[i], paths[i], null, raw));
            var now = Rules.classify(new RequestFacts(apis[i], methods[i], paths[i], null,
                    skeleton.getBytes(StandardCharsets.UTF_8)));

            var same = was.tier() == now.tier() && was.operation().equals(now.operation())
                    && was.itemCount() == now.itemCount();
            check("replays identically: " + was.tier() + " / " + was.operation()
                    + " / n=" + was.itemCount(), same);
            if (!same) System.out.println("      skeleton gave: " + now.tier() + " / " + now.operation()
                    + " / n=" + now.itemCount());

            var leaked = (String) null;
            for (var c : CONTENT) if (skeleton.contains(c)) leaked = c;
            check("carries no content" + (leaked == null ? "" : " — LEAKED " + leaked), leaked == null);
        }

        var big = new StringBuilder("{\"data\":[");
        for (var i = 0; i < 500; i++) {
            big.append(i == 0 ? "" : ",").append("{\"range\":\"Sheet1!A").append(i + 1)
               .append(":C").append(i + 1).append("\",\"values\":[[\"SECRET-CELL-VALUE\"]]}");
        }
        big.append("],\"valueInputOption\":\"RAW\"}");
        var skeleton = Skeleton.of(big.toString().getBytes(StandardCharsets.UTF_8));
        check("a 500-range write keeps its count",
                BatchRequests.countArray(skeleton.getBytes(StandardCharsets.UTF_8), "data") == 500);
        check("and still carries no content", !skeleton.contains("SECRET-CELL-VALUE"));
        check("and is bounded in size", skeleton.length() <= 32768);
        check("and is still valid json", parses(skeleton));
        check("and was not structurally shortened", !skeleton.contains(Skeleton.TRUNCATED));
        System.out.println("      (500-range skeleton is " + skeleton.length() + " chars, from "
                + big.length() + ")");

        // Past the cap it shortens structurally and says so, rather than becoming a fragment that no
        // longer parses — which is what it used to do, on exactly the batches worth recording.
        var huge = new StringBuilder("{\"data\":[");
        for (var i = 0; i < 5000; i++) {
            huge.append(i == 0 ? "" : ",").append("{\"range\":\"Sheet1!A").append(i + 1)
                .append(":C").append(i + 1).append("\",\"values\":[[\"SECRET-CELL-VALUE\"]]}");
        }
        huge.append("]}");
        var cut = Skeleton.of(huge.toString().getBytes(StandardCharsets.UTF_8));
        check("a body past the cap is still valid json", parses(cut));
        check("and admits it was shortened", cut.contains(Skeleton.TRUNCATED));
        check("and still carries no content", !cut.contains("SECRET-CELL-VALUE"));

        check("an unreadable body is recorded as a fact, not dropped",
                Skeleton.of("not json".getBytes(StandardCharsets.UTF_8)).contains("not json"));
        check("no body means no skeleton", Skeleton.of(null) == null);

        System.out.println(failures == 0 ? "\nALL PASS" : "\n" + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static boolean parses(String s) {
        try {
            return com.google.gson.JsonParser.parseString(s).isJsonObject();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void check(String what, boolean ok) {
        if (!ok) failures++;
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
    }

    private SkeletonProbe() {
    }
}
