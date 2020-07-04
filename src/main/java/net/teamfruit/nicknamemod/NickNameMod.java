package net.teamfruit.nicknamemod;

import com.mojang.authlib.GameProfile;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.SPacketDestroyEntities;
import net.minecraft.network.play.server.SPacketPlayerListItem;
import net.minecraft.network.play.server.SPacketSpawnPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerList;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Mod(
        modid = NickNameMod.MOD_ID,
        name = NickNameMod.MOD_NAME,
        version = NickNameMod.VERSION,
        acceptableRemoteVersions = "*"
)
public class NickNameMod {

    public static final String MOD_ID = "nicknamemod";
    public static final String MOD_NAME = "NickNameMod";
    public static final String VERSION = "1.0-SNAPSHOT";

    /**
     * This is the instance of your mod as created by Forge. It will never be null.
     */
    @Mod.Instance(MOD_ID)
    public static NickNameMod INSTANCE;

    public File nicksPath;
    public NickModel nicks;

    // This prefix is the universal prefix for the plugin.
    public static final String PREFIX = TextFormatting.RESET + "[" + TextFormatting
            .GOLD + "NickNames" + TextFormatting.RESET + "]";

    /**
     * This is the first initialization event. Register tile entities here.
     * The registry events below will have fired prior to entry to this method.
     */
    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(this);

        // Make the plugin directory if it doesn't exist
        event.getModConfigurationDirectory().mkdir();

        nicksPath = new File(event.getModConfigurationDirectory(), "nicks.json");
        nicks = DataUtils.loadFileIfExists(nicksPath, NickModel.class, "Nick Names List");
        if (nicks == null)
            nicks = new NickModel();
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandBase() {
            @Override public String getName() {
                return "nick";
            }

            @Override public String getUsage(ICommandSender sender) {
                return "Usage:" +
                        "/nick [Player] [Nickname] - Nick player";
            }

            @Override public int getRequiredPermissionLevel() {
                return 3;
            }

            @Override public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
                // Setting someone else's nickname
                // Get the name of the target player
                String targetPlayer = args[0];

                //Make the syntax correct. & is a popular way to set colours, but bukkit only accepts the §.
                String newName = args.length < 2 ? null :
                        StringUtils.truncate(args[1].replace("&", "§"), 16);

                // Target starts as null.
                List<EntityPlayerMP> targets = getPlayers(server, sender, targetPlayer);

                // If the search was successful run the commmand
                if (!targets.isEmpty()) {
                    for (EntityPlayerMP target : targets)
                        setNick(target, newName);
                    sendNickWithFeedback(sender, targetPlayer, newName);
                    return;
                }
                // Else throw an error
                else {
                    sender.sendMessage(new TextComponentString("That player is not currently online."));
                }

                sender.sendMessage(new TextComponentString(TextFormatting.DARK_RED + "Syntax Error!"));
                sender.sendMessage(new TextComponentString(getUsage(sender)));
                return;
            }
        });
    }

    /**
     * Sets the nickname of a given user.
     *
     * @param player  the target player
     * @param newName the new name
     */
    private void setNick(
            EntityPlayerMP player,
            String newName
    ) {
        if (newName == null)
            nicks.nicks.remove(player.getName().toLowerCase());
        else
            nicks.nicks.put(player.getName().toLowerCase(), newName);
        DataUtils.saveFile(nicksPath, NickModel.class, nicks, "Nick Names List");

        // Change the display name of the user to the new name
        new NickApplier(player, newName).applyServer().applyToAll();
    }

    private void sendNickWithFeedback(
            ICommandSender sender,
            String targetName,
            String newName
    ) {
        // Mention it in the logs
        Log.log.info("Changed " + targetName
                + TextFormatting.RESET + "'s name to " + newName
                + TextFormatting.RESET + ".");

        // Feedback
        sender.sendMessage(new TextComponentString(
                PREFIX + " Changed " + targetName
                        + TextFormatting.RESET + "'s name to " + newName
                        + TextFormatting.RESET + "."));
    }

    public static class NickApplier {
        private final String newName;
        private final EntityPlayerMP player;
        private final Packet<?> packetDestroy;
        private final Packet<?> packetRemove;
        private final Packet<?> packetAdd;
        private final Packet<?> packetSpawn;

        public NickApplier(
                EntityPlayerMP player,
                String newName
        ) {
            this.newName = newName;
            this.player = player;
            {
                SPacketDestroyEntities packet = new SPacketDestroyEntities(player.getEntityId());
                packetDestroy = packet;
            }
            {
                SPacketPlayerListItem packet = new SPacketPlayerListItem(SPacketPlayerListItem.Action.REMOVE_PLAYER, player);
                packetRemove = packet;
            }
            {
                SPacketPlayerListItem packet = new SPacketPlayerListItem(SPacketPlayerListItem.Action.ADD_PLAYER, player);
                if (newName != null) {
                    packet.players.forEach(e -> {
                        GameProfile p = new GameProfile(e.profile.getId(), newName);
                        p.getProperties().putAll(e.profile.getProperties());
                        e.profile = p;
                    });
                    packet.players.forEach(e -> e.displayName = new TextComponentString(newName));
                }
                packetAdd = packet;
            }
            {
                SPacketSpawnPlayer packet = new SPacketSpawnPlayer(player);
                packetSpawn = packet;
            }
        }

        public NickApplier applyServer() {
            // Forge Replace
            try {
                ObfuscationReflectionHelper.setPrivateValue(EntityPlayer.class, player, newName, "displayname");
            } catch (ReflectionHelper.UnableToAccessFieldException e) {
            }
            // Mohist Replace
            try {
                ObfuscationReflectionHelper.setPrivateValue(EntityPlayerMP.class, player, newName == null ? player.getName() : newName, "displayName");
            } catch (ReflectionHelper.UnableToAccessFieldException e) {
            }
            return this;
        }

        public NickApplier applyToAll() {
            PlayerList playerList = FMLCommonHandler.instance().getMinecraftServerInstance().getPlayerList();
            playerList.getPlayers().stream().filter(e -> !e.equals(player)).forEach(e -> e.connection.sendPacket(packetDestroy));
            playerList.sendPacketToAllPlayers(packetRemove);
            playerList.sendPacketToAllPlayers(packetAdd);
            playerList.getPlayers().stream().filter(e -> !e.equals(player)).forEach(e -> e.connection.sendPacket(packetSpawn));
            return this;
        }

        public NickApplier applyTo(EntityPlayerMP sendTo) {
            if (!sendTo.equals(player))
                sendTo.connection.sendPacket(packetDestroy);
            sendTo.connection.sendPacket(packetRemove);
            sendTo.connection.sendPacket(packetAdd);
            if (!sendTo.equals(player))
                sendTo.connection.sendPacket(packetSpawn);
            return this;
        }
    }

    @SubscribeEvent
    public void onDisplayName(net.minecraftforge.event.entity.player.PlayerEvent.NameFormat event) {
        String nickname = nicks.nicks.get(event.getUsername());
        if (nickname != null)
            event.setDisplayname(nickname);
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.player instanceof EntityPlayerMP))
            return;

        EntityPlayerMP player = (EntityPlayerMP) event.player;

        String newName = nicks.nicks.get(player.getName().toLowerCase());

        if (newName != null) {
            new NickApplier(player, newName).applyServer().applyToAll();
        }

        PlayerList playerList = FMLCommonHandler.instance().getMinecraftServerInstance().getPlayerList();
        for (EntityPlayerMP target : playerList.getPlayers()) {
            String nick = nicks.nicks.get(target.getName().toLowerCase());
            if (nick != null) {
                new NickApplier(target, nick).applyTo(player);
            }
        }
    }

}
