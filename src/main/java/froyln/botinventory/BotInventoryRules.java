package froyln.botinventory;

import carpet.api.settings.Rule;

import static carpet.api.settings.RuleCategory.COMMAND;
import static carpet.api.settings.RuleCategory.EXPERIMENTAL;

public class BotInventoryRules {
    @Rule(
        categories = {EXPERIMENTAL, COMMAND},
        options = {"true", "false", "ops", "0", "1", "2", "3", "4"},
        strict = false
    )
    public static String viewFakePlayerInventoryRightClick = "false";

    @Rule(
        categories = {COMMAND},
        options = {"true", "false", "ops", "0", "1", "2", "3", "4"},
        strict = false
    )
    public static String viewPlayerInventoryCommand = "false";

    @Rule(
        categories = {COMMAND},
        options = {"true", "false", "ops", "0", "1", "2", "3", "4"},
        strict = false
    )
    public static String viewPlayerEnderchestCommand = "false";

    @Rule(
        categories = {COMMAND},
        options = {"true", "false", "ops", "0", "1", "2", "3", "4"},
        strict = false
    )
    public static String viewOfflinePlayerInventory = "false";

    // Defaults true: getTargetPlayer never distinguished bots from real
    // players before this rule existed, so any online player was already
    // viewable with just viewPlayerInventoryCommand. Defaulting false here
    // would silently break existing setups on upgrade.
    @Rule(
        categories = {COMMAND},
        options = {"true", "false", "ops", "0", "1", "2", "3", "4"},
        strict = false
    )
    public static String viewRealPlayerInventory = "true";
}
