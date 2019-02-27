package org.totemcraft.msgfltr;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.sainttx.auctions.util.ReflectionUtil;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.chat.ComponentSerializer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MessageFilterPlugin extends JavaPlugin {

    private final static Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\$\\((?<capture>[0-9a-zA-Z_]*)\\)");

    private final List<ReplaceEntry> replaceEntries = new ArrayList<>();
    private boolean sniffer = false;

    private PacketHandler packetHandler;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        List<Map<?, ?>> mapList = getConfig().getMapList("replaces");
        if (mapList != null) {
            for (Map<?, ?> entries : mapList) {
                ReplaceEntry replaceEntry = ReplaceEntry.read(entries);
                replaceEntries.add(replaceEntry);
                getLogger().info("读入替换条目: " + replaceEntry);
            }
        }

        sniffer = getConfig().getBoolean("sniffer", false);
        getLogger().info("sniffer = " + sniffer);

        packetHandler = new PacketHandler(this);
        ProtocolLibrary.getProtocolManager().addPacketListener(packetHandler);
    }

    @Override
    public void onDisable() {
        if (packetHandler != null) {
            ProtocolLibrary.getProtocolManager().removePacketListener(packetHandler);
        }
        replaceEntries.clear();
    }

    private synchronized String performReplace(String content, MessageSource messageSource) throws NotChangedSignal {
        if (sniffer && !content.isEmpty()) getLogger().warning("[SNIFFER] " + content);
        boolean changed = false;
        for (ReplaceEntry replaceEntry : replaceEntries) {
            if (!replaceEntry.sources.contains(messageSource)) continue;
            Matcher contentMatcher = replaceEntry.contentPattern.matcher(content);
            StringBuffer contentBuffer = new StringBuffer();
            while (contentMatcher.find()) {
                changed = true;
                Map<String, String> token = new HashMap<>();
                for (String captureName : replaceEntry.namedCaptureList) {
                    String value = contentMatcher.group(captureName);
                    token.put(captureName, value == null ? "" : value);
                }
                Matcher replaceMatcher = PLACEHOLDER_PATTERN.matcher(replaceEntry.replace);
                StringBuffer replaceBuffer = new StringBuffer();
                while (replaceMatcher.find()) {
                    String namedCapture = replaceMatcher.group("capture");
                    String value = token.get(namedCapture);
                    if (value == null) value = "";
                    replaceMatcher.appendReplacement(replaceBuffer, value);
                }
                replaceMatcher.appendTail(replaceBuffer);
                contentMatcher.appendReplacement(contentBuffer, replaceBuffer.toString());
            }
            if (!changed) continue;
            contentMatcher.appendTail(contentBuffer);
            content = contentBuffer.toString();
        }
        if (!changed) throw NotChangedSignal.INSTANCE;
        return content;
    }

    private List<String> performReplace(List<String> content, MessageSource messageSource) throws NotChangedSignal {
        if (sniffer && !content.isEmpty()) getLogger().warning(content.toString());
        ListIterator<String> listIterator = content.listIterator();
        boolean changed = false;
        while (listIterator.hasNext()) {
            try {
                listIterator.set(performReplace(listIterator.next(), messageSource));
                changed = true;
            } catch (NotChangedSignal ignored) {
            }
        }
        if (!changed) throw NotChangedSignal.INSTANCE;
        return content;
    }

    private ItemStack performReplace(ItemStack item, MessageSource messageSource) throws NotChangedSignal {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) throw NotChangedSignal.INSTANCE;

        boolean changed = false;

        String title = meta.getDisplayName();
        if (title != null) {
            try {
                title = performReplace(title, messageSource);
                changed = true;
            } catch (NotChangedSignal ignored) {
            }
        }
        if (changed) meta.setDisplayName(title);

        List<String> lore = meta.getLore();
        if (lore != null) {
            try {
                lore = performReplace(lore, messageSource);
                changed = true;
            } catch (NotChangedSignal ignored) {
            }
        }
        if (!changed) throw NotChangedSignal.INSTANCE;
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    @SuppressWarnings("unchecked")
    private static List<String> getNamedGroupsFromPattern(Pattern regex) {
        try {
            Method namedGroupsMethod = Pattern.class.getDeclaredMethod("namedGroups");
            namedGroupsMethod.setAccessible(true);

            SortedMap<Integer, String> sortedMap = new TreeMap<>();
            for (Map.Entry<String, Integer> stringIntegerEntry : ((Map<String, Integer>) namedGroupsMethod.invoke(regex)).entrySet()) {
                sortedMap.put(stringIntegerEntry.getValue(), stringIntegerEntry.getKey());
            }

            return new ArrayList<>(sortedMap.values());
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private class PacketHandler extends PacketAdapter {
        PacketHandler(Plugin plugin) {
            super(plugin, PacketType.Play.Server.CHAT,
                    PacketType.Play.Server.TITLE,
                    PacketType.Play.Server.OPEN_WINDOW,
                    PacketType.Play.Server.SET_SLOT,
                    PacketType.Play.Server.WINDOW_ITEMS,
                    PacketType.Play.Server.BOSS,
                    PacketType.Play.Server.SCOREBOARD_OBJECTIVE,
                    PacketType.Play.Server.SCOREBOARD_SCORE
            );
        }

        @Override
        public void onPacketSending(PacketEvent event) {
            try {
                if (event.getPacketType() == PacketType.Play.Server.CHAT) {
                    handleComponentReplace(event.getPacket(), MessageSource.CHAT);
                } else if (event.getPacketType() == PacketType.Play.Server.TITLE) {
                    EnumWrappers.TitleAction titleAction = event.getPacket().getTitleActions().read(0);
                    if (titleAction == EnumWrappers.TitleAction.TITLE ||
                            titleAction == EnumWrappers.TitleAction.SUBTITLE) {
                        handleComponentReplace(event.getPacket(), MessageSource.TITLE);
                    }
                } else if (event.getPacketType() == PacketType.Play.Server.OPEN_WINDOW) {
                    handleComponentReplace(event.getPacket(), MessageSource.INV);
                } else if (event.getPacketType() == PacketType.Play.Server.SET_SLOT) {
                    ItemStack item = event.getPacket().getItemModifier().read(0);
                    if (item != null) {
                        try {
                            item = performReplace(item, MessageSource.ITEM);
                        } catch (NotChangedSignal notChangedSignal) {
                            return;
                        }
                        event.getPacket().getItemModifier().write(0, item);
                    }
                } else if (event.getPacketType() == PacketType.Play.Server.WINDOW_ITEMS) {
                    if (event.getPacket().getItemArrayModifier().size() > 0) {
                        ItemStack[] itemStacks = event.getPacket().getItemArrayModifier().read(0);
                        boolean changed = false;
                        for (int i = 0; i < itemStacks.length; i++) {
                            if (itemStacks[i] == null) continue;
                            try {
                                itemStacks[i] = performReplace(itemStacks[i], MessageSource.ITEM);
                                changed = true;
                            } catch (NotChangedSignal ignored) {
                            }
                        }
                        if (!changed) return;
                        event.getPacket().getItemArrayModifier().write(0, itemStacks);
                    } else if (event.getPacket().getItemListModifier().size() > 0) {
                        List<ItemStack> itemStacks = event.getPacket().getItemListModifier().read(0);
                        boolean changed = false;
                        for (int i = 0; i < itemStacks.size(); i++) {
                            if (itemStacks.get(i) == null) continue;
                            try {
                                itemStacks.set(i, performReplace(itemStacks.get(i), MessageSource.ITEM));
                                changed = true;
                            } catch (NotChangedSignal ignored) {
                            }
                        }
                        if (!changed) return;
                        event.getPacket().getItemListModifier().write(0, itemStacks);
                    }
                } else if (event.getPacketType() == PacketType.Play.Server.BOSS) {
                    //noinspection unchecked
                    StructureModifier<Enum> structureModifier = event.getPacket().getEnumModifier(((Class<Enum>) ReflectionUtil.getNMSClass("PacketPlayOutBoss$Action")), 3);
                    if (structureModifier.read(0).ordinal() == 0 || structureModifier.read(0).ordinal() == 3) {
                        handleComponentReplace(event.getPacket(), MessageSource.BOSSBAR);
                    }
                } else if (event.getPacketType() == PacketType.Play.Server.SCOREBOARD_OBJECTIVE) {
                    String displayName = event.getPacket().getStrings().read(1);
                    if (displayName == null) return;
                    try {
                        displayName = performReplace(displayName, MessageSource.SCOREBOARD);
                    } catch (NotChangedSignal notChangedSignal) {
                        return;
                    }
                    event.getPacket().getStrings().write(1, displayName);
                } else if (event.getPacketType() == PacketType.Play.Server.SCOREBOARD_SCORE) {
                    String identifier = event.getPacket().getStrings().read(0);
                    if (identifier == null) return;
                    try {
                        identifier = performReplace(identifier, MessageSource.SCOREBOARD);
                    } catch (NotChangedSignal notChangedSignal) {
                        return;
                    }
                    event.getPacket().getStrings().write(0, identifier);
                }
            } catch (CancelSendSignal cancelSendSignal) {
                event.setCancelled(true);
            }
        }

        private void handleComponentReplace(PacketContainer packet, MessageSource messageSource) throws CancelSendSignal {
            WrappedChatComponent wrappedChatComponent = packet.getChatComponents().read(0);
            if (wrappedChatComponent == null) return;
            String legacyText = BaseComponent.toLegacyText(ComponentSerializer.parse(wrappedChatComponent.getJson()));
            try {
                legacyText = performReplace(legacyText, messageSource);
            } catch (NotChangedSignal notChangedSignal) {
                return;
            }
            if (legacyText.isEmpty() && messageSource == MessageSource.CHAT) throw CancelSendSignal.INSTANCE;
            packet.getChatComponents().write(0, WrappedChatComponent.fromJson(ComponentSerializer.toString(new TextComponent(legacyText))));
        }
    }

    private enum MessageSource {
        CHAT, TITLE, INV, ITEM, BOSSBAR, SCOREBOARD;

        static MessageSource getByName(String name) {
            for (MessageSource messageSource : values()) {
                if (messageSource.name().toLowerCase().equals(name)) return messageSource;
            }
            return null;
        }
    }

    private static class ReplaceEntry {
        private final Pattern contentPattern;
        private final String replace;
        private final Set<MessageSource> sources;

        private final List<String> namedCaptureList;

        private ReplaceEntry(Pattern contentPattern, String replace, Set<MessageSource> sources) {
            this.contentPattern = contentPattern;
            this.replace = replace;
            this.namedCaptureList = getNamedGroupsFromPattern(contentPattern);
            this.sources = sources;
        }

        static ReplaceEntry read(Map<?, ?> map) {
            String pattern = (String) map.get("pattern");
            if (pattern == null) throw new RuntimeException("pattern must be defined in replace entry");
            String replace = (String) map.get("replace");
            if (replace == null) replace = "";

            Set<MessageSource> messageSources = new HashSet<>();
            //noinspection unchecked
            List<String> placeList = (List<String>) map.get("sources");
            if (placeList != null) {
                for (String placeName : placeList) {
                    MessageSource messageSource = MessageSource.getByName(placeName);
                    if (messageSource != null) messageSources.add(messageSource);
                }
            }
            return new ReplaceEntry(Pattern.compile(pattern), replace, messageSources);
        }

        @Override
        public String toString() {
            return String.format("PacketReplace(contentPattern=%s, replace=%s, sources=%s, namedCaptureList=%s)",
                    contentPattern.pattern(), replace, sources.toString(), namedCaptureList);
        }
    }

    private static class NotChangedSignal extends Throwable {
        private final static NotChangedSignal INSTANCE = new NotChangedSignal();

        private NotChangedSignal() {
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    private static class CancelSendSignal extends Throwable {
        private final static CancelSendSignal INSTANCE = new CancelSendSignal();

        private CancelSendSignal() {
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            return null;
        }
    }
}
