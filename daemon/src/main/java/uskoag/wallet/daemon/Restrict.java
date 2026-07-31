package uskoag.wallet.daemon;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.util.EnumSet;
import java.util.List;

/**
 * Narrows a file's ACL to its owner.
 *
 * <p>Loopback TCP is reachable by any process running as any user on this machine, so the handshake
 * token is what keeps a stranger from calling the wallet's verbs — and the token is only as private as
 * the file holding it. On a POSIX filesystem this falls back to 0600.
 */
public final class Restrict {

    private Restrict() {
    }

    public static void toOwner(Path file) {
        try {
            var acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
            if (acl != null) {
                var owner = Files.getOwner(file);
                acl.setAcl(List.of(AclEntry.newBuilder()
                        .setType(AclEntryType.ALLOW)
                        .setPrincipal(owner)
                        .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                        .build()));
                return;
            }
            Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (Exception e) {
            Log.warn("could not restrict permissions on " + file + " — " + e);
        }
    }
}
