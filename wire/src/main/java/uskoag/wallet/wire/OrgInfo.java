package uskoag.wallet.wire;

import java.util.List;

/**
 * One organisation's OAuth client, as seen from outside the wallet. The secret never appears here.
 *
 * @param owner the account this client was created under, if known. Advisory: a client in Testing
 *              status only lets listed test users consent, so granting a different account under it
 *              often fails in a way Google explains badly.
 */
public record OrgInfo(String id, String label, String clientId, String owner,
                      List<String> domains, int accounts, long addedAt) {
}
