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
}
