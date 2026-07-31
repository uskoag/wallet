package uskoag.wallet.ui;

import java.util.List;

/** What the Grant dialog came back with: named groups, plus anything typed by hand. */
public record Grant(List<String> groups, List<String> customScopes) {

    public boolean isEmpty() {
        return groups.isEmpty() && customScopes.isEmpty();
    }
}
