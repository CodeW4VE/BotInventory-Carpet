package froyln.botinventory;

import carpet.api.rule.Rule;

public class BotInventoryRules {
    @Rule(
        desc = "Allows right click in fake player to view their inventories",
        options = {"true", "false", "op", "0", "1", "2", "3", "4"}
    )
    public static String clickFakePlayerInventory = "false";

    @Rule(
        desc = "Command to view a player's inventory",
        options = {"true", "false", "op", "0", "1", "2", "3", "4"}
    )
    public static String viewPlayerInventoryCommand = "false";

    @Rule(
        desc = "Command to view a player's enderchest",
        options = {"true", "false", "op", "0", "1", "2", "3", "4"}
    )
    public static String viewPlayerEnderchestCommand = "false";
}
