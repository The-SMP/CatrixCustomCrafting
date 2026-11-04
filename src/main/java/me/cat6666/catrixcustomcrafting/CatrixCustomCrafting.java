package me.cat6666.catrixcustomcrafting;

import me.cat6666.catrixcustomcrafting.data.RecipeData;
import me.cat6666.catrixcustomcrafting.data.RecipeStore;
import me.cat6666.catrixcustomcrafting.update.VersionChecker;
import me.cat6666.catrixcustomcrafting.gui.EditorState;
import me.cat6666.catrixcustomcrafting.gui.GuiHolder;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public final class CatrixCustomCrafting extends JavaPlugin implements Listener {
    private final Map<UUID, RecipeData> recipes = new LinkedHashMap<>();
    private final Map<UUID, EditorState> editors = new HashMap<>();
    private RecipeStore recipeStore;
    private VersionChecker versionChecker;
    private WorkbenchListener workbench;
    private final Map<String, String> messageCache = new HashMap<>();
    private NamespacedKey guiKey;
    private BukkitTask saveTask;
    private ExecutorService io;
    private ExecutorService updateExecutor;

    @Override public void onEnable() {
        io = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "CatrixCraft-IO"); t.setDaemon(true); return t; });
        updateExecutor = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "CatrixCraft-Update"); t.setDaemon(true); return t; });
        recipeStore = new RecipeStore(this);
        versionChecker = new VersionChecker(this, updateExecutor, this::msg);
        workbench = new WorkbenchListener(this);
        guiKey = new NamespacedKey(this, "gui");
        mergeResource("config.yml"); mergeResource("messages.yml"); reloadConfig(); migrateRepositorySetting(); loadMessages();
        loadRecipes();
        CraftCommand craftCommand = new CraftCommand(this, versionChecker);
        Objects.requireNonNull(getCommand("ccraft")).setExecutor(craftCommand);
        Objects.requireNonNull(getCommand("ccraft")).setTabCompleter(craftCommand);
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(workbench, this);
        saveTask = Bukkit.getScheduler().runTaskTimer(this, this::flushIfDirty, 20L * Math.max(30, getConfig().getInt("settings.autosave-seconds", 120)), 20L * Math.max(30, getConfig().getInt("settings.autosave-seconds", 120)));
        if (getConfig().getBoolean("settings.check-updates", true)) versionChecker.checkAtStartup();
        getLogger().info("CatrixCustomCrafting 1.0.0 已啟用，共載入 " + recipes.size() + " 個配方。");
    }

    @Override public void onDisable() {
        if (saveTask != null) saveTask.cancel();
        flushIfDirty();
        editors.clear();
        if (updateExecutor != null) updateExecutor.shutdownNow();
        if (io != null) {
            io.shutdown();
            try { if (!io.awaitTermination(10, TimeUnit.SECONDS)) io.shutdownNow(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); io.shutdownNow(); }
        }
    }

    private void mergeResource(String name) {
        File target = new File(getDataFolder(), name);
        if (!target.exists()) saveResource(name, false);
        YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(Objects.requireNonNull(getResource(name)), java.nio.charset.StandardCharsets.UTF_8));
        YamlConfiguration current = YamlConfiguration.loadConfiguration(target);
        mergeSection(defaults, current, "");
        try { current.save(target); } catch (IOException e) { getLogger().severe("無法寫入 " + name + ": " + e.getMessage()); }
    }
    private void mergeSection(ConfigurationSection source, YamlConfiguration target, String path) {
        for (String key : source.getKeys(false)) {
            String full = path.isEmpty() ? key : path + "." + key;
            if (source.isConfigurationSection(key)) mergeSection(source.getConfigurationSection(key), target, full);
            else if (!target.contains(full)) target.set(full, source.get(full));
        }
    }
    private void migrateRepositorySetting() {
        String repository = getConfig().getString("settings.github-repository", "The-SMP/CatrixCustomCrafting");
        if ("cat6666/CatrixCustomCrafting".equalsIgnoreCase(repository) || "me-cat6666/CatrixCustomCrafting".equalsIgnoreCase(repository)) {
            getConfig().set("settings.github-repository", "The-SMP/CatrixCustomCrafting");
            saveConfig();
        }
    }
    private void loadMessages() { messageCache.clear(); YamlConfiguration y=YamlConfiguration.loadConfiguration(new File(getDataFolder(),"messages.yml")); for(String key:y.getKeys(true)) if(!y.isConfigurationSection(key)) messageCache.put(key,y.getString(key,"")); }
    private void loadRecipes() {
        recipes.keySet().forEach(workbench::unregisterWorkbenchRecipe);
        recipes.clear();
        recipes.putAll(recipeStore.load());
        recipes.values().forEach(workbench::registerWorkbenchRecipe);
    }

    private void flushIfDirty() {
        if (recipeStore != null) recipeStore.flush(io);
    }

    String msg(String key, Map<String,String> vars) {
        String value = messageCache.getOrDefault(key, key);
        for (var entry : vars.entrySet()) value = value.replace("%" + entry.getKey() + "%", entry.getValue());
        return ChatColor.translateAlternateColorCodes('&', value);
    }
    String msg(String key) { return msg(key, Map.of()); }
    void tell(CommandSender sender, String key) { sender.sendMessage(msg("prefix") + msg(key)); }
    private ItemStack named(Material material, String nameKey, String loreKey) {
        ItemStack item = new ItemStack(material); ItemMeta meta = item.getItemMeta(); meta.setDisplayName(msg(nameKey));
        if (loreKey != null) meta.setLore(List.of(msg(loreKey))); item.setItemMeta(meta); return item;
    }
    private void sign(Inventory inv, String kind) { ItemStack marker = new ItemStack(Material.PAPER); ItemMeta meta = marker.getItemMeta(); meta.getPersistentDataContainer().set(guiKey, PersistentDataType.STRING, kind); marker.setItemMeta(meta); inv.setItem(inv.getSize()-1, marker); }
    private boolean isSigned(Inventory inv) { if (!(inv.getHolder() instanceof GuiHolder holder) || holder.plugin != this) return false; ItemStack marker = inv.getItem(inv.getSize()-1); return marker != null && marker.hasItemMeta() && marker.getItemMeta().getPersistentDataContainer().has(guiKey, PersistentDataType.STRING); }
    void openEditor(Player player) { openEditor(player, null); }
    void openEditor(Player player, RecipeData existing) {
        GuiHolder holder = new GuiHolder(this); Inventory inv = Bukkit.createInventory(holder, 54, msg("editor-title")); holder.inventory = inv; sign(inv, "editor");
        inv.setItem(45, named(Material.BARRIER,"cancel-name","cancel-lore")); inv.setItem(49, named(Material.EMERALD_BLOCK,"save-name","save-lore"));
        ItemStack glass = named(Material.GRAY_STAINED_GLASS_PANE,"ingredient-slots-name",null);
        for (int i=0;i<54;i++) if (i<45 && !isInputSlot(i)) inv.setItem(i, glass);
        EditorState state = new EditorState(existing == null ? null : existing.id,existing==null||existing.exactMatch);
        if (existing != null) {
            int[] slots={10,11,12,19,20,21,28,29,30};
            for(int i=0;i<9;i++) if(existing.ingredients[i]!=null) inv.setItem(slots[i],existing.ingredients[i].clone());
            inv.setItem(24,existing.result.clone());
        }
        updateMatchButton(inv,state);
        player.openInventory(inv); editors.put(player.getUniqueId(),state);
    }
    private boolean isInputSlot(int slot) { return slot==10||slot==11||slot==12||slot==19||slot==20||slot==21||slot==28||slot==29||slot==30||slot==24; }
    private void updateMatchButton(Inventory inv,EditorState state){inv.setItem(47,named(Material.COMPARATOR,state.exactMatch?"match-exact-name":"match-similar-name",state.exactMatch?"match-exact-lore":"match-similar-lore"));}
    private void saveEditor(Player player, Inventory inv) {
        ItemStack[] ingredients=new ItemStack[9]; int[] slots={10,11,12,19,20,21,28,29,30}; boolean any=false;
        for(int i=0;i<9;i++){ItemStack item=inv.getItem(slots[i]);if(item!=null&&!item.getType().isAir()){ingredients[i]=item.clone();ingredients[i].setAmount(1);any=true;}}
        ItemStack result=inv.getItem(24);
        if(!any||result==null||result.getType().isAir()){tell(player,"save-invalid");return;}
        EditorState state=editors.get(player.getUniqueId());
        UUID id=state!=null&&state.recipeId!=null?state.recipeId:UUID.randomUUID();
        RecipeData saved = new RecipeData(id, ingredients, result.asOne(), state == null || state.exactMatch);
        RecipeData previous = recipes.get(id);
        if (previous == null || previous.exactMatch != saved.exactMatch
                || !Arrays.equals(previous.ingredients, saved.ingredients) || !previous.result.equals(saved.result)) {
            recipes.put(id, saved);
            recipeStore.markChanged(id, saved);
            workbench.registerWorkbenchRecipe(saved);
        }
        editors.remove(player.getUniqueId()); player.sendMessage(msg("prefix")+msg("saved",Map.of("id",id.toString()))); openRecipe(player,saved);
    }
    void openMenu(Player player, int page) {
        GuiHolder holder=new GuiHolder(this); Inventory inv=Bukkit.createInventory(holder,54,msg("menu-title"));holder.inventory=inv;sign(inv,"menu:"+page);
        List<RecipeData> all=new ArrayList<>(recipes.values()); int pageSize=34, pages=Math.max(1,(all.size()+pageSize-1)/pageSize); page=Math.max(0,Math.min(page,pages-1));
        inv.setItem(49,named(Material.EMERALD_BLOCK,"create-name","create-lore"));
        inv.setItem(50,named(Material.PAPER,"page-name",null));
        if(page>0)inv.setItem(45,named(Material.ARROW,"previous-page-name","previous-page-lore"));
        if(page+1<pages)inv.setItem(51,named(Material.ARROW,"next-page-name","next-page-lore"));
        if(all.isEmpty())inv.setItem(22,named(Material.BOOK,"recipes-empty-name","recipes-empty-lore"));
        int[] slots=new int[34];int n=0;for(int slot=10;slot<=43;slot++)slots[n++]=slot;
        for(int i=page*pageSize;i<Math.min(all.size(),(page+1)*pageSize);i++){
            RecipeData recipe=all.get(i);ItemStack icon=recipe.result.clone();ItemMeta meta=icon.getItemMeta();List<String> lore=meta.hasLore()?new ArrayList<>(meta.getLore()):new ArrayList<>();lore.add(msg("recipe-entry-lore"));meta.setLore(lore);icon.setItemMeta(meta);inv.setItem(slots[i-page*pageSize],icon);
        }
        player.openInventory(inv);
    }
    void openRecipe(Player player, RecipeData recipe) {
        GuiHolder holder=new GuiHolder(this); Inventory inv=Bukkit.createInventory(holder,54,msg("recipe-title",Map.of("id",recipe.id.toString().substring(0,8))));holder.inventory=inv; sign(inv,"recipe:"+recipe.id);
        int[] slots={10,11,12,19,20,21,28,29,30};
        for(int i=0;i<9;i++) if(recipe.ingredients[i]!=null) inv.setItem(slots[i],recipe.ingredients[i].clone());
        ItemStack output=recipe.result.clone(); ItemMeta meta=output.getItemMeta(); List<String> lore=meta.hasLore()?new ArrayList<>(meta.getLore()):new ArrayList<>(); lore.add(msg("recipe-output-lore")); meta.setLore(lore); output.setItemMeta(meta); inv.setItem(24,output);
        if(player.hasPermission(getConfig().getString("permissions.create","catrixcustomcrafting.create"))){inv.setItem(46,named(Material.WRITABLE_BOOK,"edit-name","edit-lore"));inv.setItem(48,named(Material.BARRIER,"delete-name","delete-lore"));}
        inv.setItem(49,named(Material.ARROW,"back-name","back-lore"));player.openInventory(inv);
    }
    private void openDeleteConfirm(Player player, RecipeData recipe) {
        GuiHolder holder=new GuiHolder(this);Inventory inv=Bukkit.createInventory(holder,27,msg("delete-confirm-title"));holder.inventory=inv;sign(inv,"delete:"+recipe.id);
        inv.setItem(11,named(Material.RED_CONCRETE,"delete-confirm-name","delete-confirm-lore"));inv.setItem(15,named(Material.BARRIER,"delete-cancel-name","delete-cancel-lore"));player.openInventory(inv);
    }
    @EventHandler public void onClick(InventoryClickEvent event) {
        Inventory top=event.getView().getTopInventory(); if(!isSigned(top)) return;
        String kind=top.getItem(top.getSize()-1).getItemMeta().getPersistentDataContainer().get(guiKey,PersistentDataType.STRING);
        if(!(event.getWhoClicked() instanceof Player player)) { event.setCancelled(true); return; }
        if(kind.equals("editor")) {
            if (!player.hasPermission(getConfig().getString("permissions.create", "catrixcustomcrafting.create"))) {
                event.setCancelled(true);
                return;
            }
            int raw=event.getRawSlot();
            if(raw>=top.getSize()) { if((event.getClick()!=ClickType.LEFT&&event.getClick()!=ClickType.RIGHT)||event.isShiftClick()||event.getHotbarButton()>=0) event.setCancelled(true); return; }
            if(raw==45){event.setCancelled(true);if(event.getClick()==ClickType.LEFT){editors.remove(player.getUniqueId());player.closeInventory();tell(player,"cancelled");}return;}
            if(raw==49){event.setCancelled(true);if(event.getClick()==ClickType.LEFT)saveEditor(player,top);return;}
            if(raw==47){event.setCancelled(true);if(event.getClick()==ClickType.LEFT){EditorState state=editors.get(player.getUniqueId());if(state!=null){state.exactMatch=!state.exactMatch;updateMatchButton(top,state);}}return;}
            if(!isInputSlot(raw)||(event.getClick()!=ClickType.LEFT&&event.getClick()!=ClickType.RIGHT)||event.isShiftClick()||event.getHotbarButton()>=0) {event.setCancelled(true);return;}
            event.setCancelled(true);
            setEditorSample(top, raw, event.getView().getCursor());
            return;
        }
        event.setCancelled(true);
        if(event.getClick()!=ClickType.LEFT||event.getRawSlot()<0||event.getRawSlot()>=top.getSize())return;
        int slot=event.getRawSlot();
        if(kind.startsWith("menu:")){
            if(!player.hasPermission(getConfig().getString("permissions.use","catrixcustomcrafting.use"))){tell(player,"no-permission");return;}
            int page;try{page=Integer.parseInt(kind.substring(5));}catch(NumberFormatException e){return;}
            if(slot==49){if(player.hasPermission(getConfig().getString("permissions.create","catrixcustomcrafting.create")))openEditor(player);else tell(player,"no-permission");return;}
            if(slot==45){openMenu(player,page-1);return;}if(slot==51){openMenu(player,page+1);return;}
            if(slot>=10&&slot<=43){int index=page*34+(slot-10);if(index<recipes.size())openRecipe(player,new ArrayList<>(recipes.values()).get(index));}
            return;
        }
        if(kind.startsWith("recipe:")){
            UUID id;try{id=UUID.fromString(kind.substring(7));}catch(IllegalArgumentException e){return;}RecipeData recipe=recipes.get(id);if(recipe==null)return;
            if(slot==24){if(player.hasPermission(getConfig().getString("permissions.use","catrixcustomcrafting.use")))tryCraft(player,recipe);else tell(player,"no-permission");}
            else if(slot==46){if(player.hasPermission(getConfig().getString("permissions.create","catrixcustomcrafting.create")))openEditor(player,recipe);else tell(player,"no-permission");}
            else if(slot==48){if(player.hasPermission(getConfig().getString("permissions.create","catrixcustomcrafting.create")))openDeleteConfirm(player,recipe);else tell(player,"no-permission");}
            else if(slot==49)openMenu(player,0);
            return;
        }
        if(kind.startsWith("delete:")){
            if(!player.hasPermission(getConfig().getString("permissions.create","catrixcustomcrafting.create"))){tell(player,"no-permission");return;}
            UUID id;try{id=UUID.fromString(kind.substring(7));}catch(IllegalArgumentException e){return;}
            if(slot==11){if(recipes.remove(id)!=null){workbench.unregisterWorkbenchRecipe(id);recipeStore.markChanged(id, null);player.sendMessage(msg("prefix")+msg("deleted"));}openMenu(player,0);}
            else if(slot==15)openMenu(player,0);
        }
    }
    private void setEditorSample(Inventory inventory, int slot, ItemStack sample) {
        inventory.setItem(slot, sample == null || sample.getType().isAir() ? null : sample.asOne());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!isSigned(top) || event.getRawSlots().stream().noneMatch(slot -> slot < top.getSize())) return;
        event.setCancelled(true);
        String kind = top.getItem(top.getSize() - 1).getItemMeta().getPersistentDataContainer().get(guiKey, PersistentDataType.STRING);
        if (!"editor".equals(kind) || !event.getWhoClicked().hasPermission(getConfig().getString("permissions.create", "catrixcustomcrafting.create"))) return;
        if (event.getRawSlots().stream().anyMatch(slot -> slot >= top.getSize() || !isInputSlot(slot))) return;
        for (int slot : event.getRawSlots()) setEditorSample(top, slot, event.getOldCursor());
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        Inventory inventory = event.getInventory();
        if (isSigned(inventory) && "editor".equals(inventory.getItem(inventory.getSize() - 1).getItemMeta().getPersistentDataContainer().get(guiKey, PersistentDataType.STRING))) {
            editors.remove(event.getPlayer().getUniqueId());
        }
    }

    private static boolean ingredientMatches(ItemStack actual, ItemStack expected, boolean exact) {
        return actual != null && expected != null && (exact ? actual.isSimilar(expected) : actual.getType() == expected.getType());
    }

    private void tryCraft(Player player, RecipeData recipe) {
        if(recipe==null)return;
        List<ItemStack> needed=new ArrayList<>();
        for(ItemStack ingredient:recipe.ingredients) if(ingredient!=null){int match=needed.stream().filter(i->ingredientMatches(i,ingredient,recipe.exactMatch)).findFirst().map(needed::indexOf).orElse(-1);if(match<0)needed.add(ingredient.clone());else needed.get(match).setAmount(needed.get(match).getAmount()+ingredient.getAmount());}
        for(ItemStack required:needed){int available=0;for(ItemStack item:player.getInventory().getStorageContents())if(item!=null&&ingredientMatches(item,required,recipe.exactMatch))available+=item.getAmount();if(available<required.getAmount()){tell(player,"ingredients-missing");return;}}
        for(ItemStack required:needed){int remain=required.getAmount();for(int i=0;i<36&&remain>0;i++){ItemStack item=player.getInventory().getItem(i);if(item!=null&&ingredientMatches(item,required,recipe.exactMatch)){int take=Math.min(remain,item.getAmount());item.setAmount(item.getAmount()-take);remain-=take;if(item.getAmount()==0)player.getInventory().setItem(i,null);}}}
        HashMap<Integer,ItemStack> overflow=player.getInventory().addItem(recipe.result.clone());for(ItemStack item:overflow.values())player.getWorld().dropItemNaturally(player.getLocation(),item);tell(player,"crafted");
    }

    Collection<RecipeData> recipes() { return recipes.values(); }
    RecipeData recipe(UUID id) { return recipes.get(id); }

    void reloadPlugin(CommandSender sender) {
        flushIfDirty();
        io.execute(() -> Bukkit.getScheduler().runTask(this, () -> {
            if (recipeStore.hasPendingChanges()) {
                tell(sender, "reload-save-failed");
                return;
            }
            mergeResource("config.yml");
            mergeResource("messages.yml");
            reloadConfig();
            migrateRepositorySetting();
            loadMessages();
            loadRecipes();
            if (saveTask != null) saveTask.cancel();
            long interval = 20L * Math.max(30, getConfig().getInt("settings.autosave-seconds", 120));
            saveTask = Bukkit.getScheduler().runTaskTimer(this, this::flushIfDirty, interval, interval);
            tell(sender, "reload");
        }));
        tell(sender, "reload-pending");
    }

    @EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTaskLater(this, () -> versionChecker.remind(id), 60L);
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        editors.remove(id);
        versionChecker.forget(id);
    }
}
