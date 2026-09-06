package uskoag.wallet.daemon;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Does the kernel actually name the process on the other end? Two checks: a connection this process makes
 * to itself must come back as this pid, and one made by a child must come back as the child's — which is
 * the part that proves the lookup discriminates rather than merely answers.
 */
public final class PeerProbe {

    public static void main(String[] args) throws Exception {
        System.out.println("available: " + PeerProcess.available()
                + (PeerProcess.available() ? "" : "  (" + PeerProcess.unavailableReason() + ")"));
        var failures = 0;

        try (var server = new ServerSocket(0, 4, InetAddress.getLoopbackAddress())) {
            var port = server.getLocalPort();

            try (var client = new Socket(InetAddress.getLoopbackAddress(), port);
                 var accepted = server.accept()) {
                var found = PeerProcess.of(client.getLocalPort(), port);
                var mine = ProcessHandle.current().pid();
                var ok = found.isPresent() && found.getAsLong() == mine;
                System.out.println((ok ? "  ok   " : "  FAIL ") + "self: expected pid " + mine
                        + ", got " + (found.isPresent() ? found.getAsLong() : "(none)"));
                if (!ok) failures++;
            }

            var child = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                    "$c = New-Object Net.Sockets.TcpClient; $c.Connect('127.0.0.1'," + port + ");"
                    + " Start-Sleep -Seconds 6").start();
            try (var accepted = server.accept()) {
                var found = PeerProcess.of(accepted.getPort(), port);
                var ok = found.isPresent() && found.getAsLong() == child.pid();
                System.out.println((ok ? "  ok   " : "  FAIL ") + "child: expected pid " + child.pid()
                        + ", got " + (found.isPresent() ? found.getAsLong() : "(none)"));
                if (!ok) failures++;

                var wrong = PeerProcess.of(accepted.getPort(), port + 1);
                var ok2 = wrong.isEmpty();
                System.out.println((ok2 ? "  ok   " : "  FAIL ")
                        + "a connection to a different port is not matched");
                if (!ok2) failures++;
            } finally {
                child.destroyForcibly();
            }
        }
        System.out.println(failures == 0 ? "\nALL PASS" : "\n" + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private PeerProbe() {
    }
}
