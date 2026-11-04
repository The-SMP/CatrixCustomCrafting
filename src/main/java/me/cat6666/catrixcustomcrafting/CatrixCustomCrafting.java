package me.cat6666.catrixcustomcrafting;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Keyed;
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
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public final class CatrixCustomCrafting extends JavaPlugin implements Listener {
    private final Map<UUID, RecipeData> recipes = new LinkedHashMap<>();
    private final Map<UUID, EditorState> editors = new HashMap<>();
    private final Set<UUID> deletedRecipeIds = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> reminderPlayers = new HashMap<>();
    private final Map<String, String> messageCache = new HashMap<>();
    private NamespacedKey guiKey;
    private BukkitTask saveTask;
    private volatile UpdateState updateState = UpdateState.unknown();
    private volatile long checkedAt;
    private volatile boolean dirty;
    private ExecutorService io;
    private String latestVersion;
    private String pluginVersion = "1.0.0";

    @Override public void onEnable() {
        pluginVersion = getDescription().getVersion();
        io = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "CatrixCraft-IO"); t.setDaemon(true); return t; });
        guiKey = new NamespacedKey(this, "gui");
        mergeResource("config.yml"); mergeResource("messages.yml"); reloadConfig(); loadMessages();
        getDataFolder().mkdirs(); new File(getDataFolder(), "custom").mkdirs();
        loadRecipes();
        CraftCommand craftCommand = new CraftCommand();
        Objects.requireNonNull(getCommand("ccraft")).setExecutor(craftCommand);
        Objects.requireNonNull(getCommand("ccraft")).setTabCompleter(craftCommand);
        VersionCommand versionCommand = new VersionCommand();
        Objects.requireNonNull(getCommand("version")).setExecutor(versionCommand);
        Objects.requireNonNull(getCommand("version")).setTabCompleter(versionCommand);
        Bukkit.getPluginManager().registerEvents(this, this);
        saveTask = Bukkit.getScheduler().runTaskTimer(this, this::flushIfDirty, 20L * Math.max(30, getConfig().getInt("settings.autosave-seconds", 120)), 20L * Math.max(30, getConfig().getInt("settings.autosave-seconds", 120)));
        if (getConfig().getBoolean("settings.check-updates", true)) checkUpdate(false);
        getLogger().info("CatrixCustomCrafting 1.0.0 已啟用，共載入 " + recipes.size() + " 個配方。");
    }

    @Override public void onDisable() {
        if (saveTask != null) saveTask.cancel();
        flushIfDirty();
        editors.clear(); reminderPlayers.clear();
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
    private void loadMessages() { messageCache.clear(); YamlConfiguration y=YamlConfiguration.loadConfiguration(new File(getDataFolder(),"messages.yml")); for(String key:y.getKeys(true)) if(!y.isConfigurationSection(key)) messageCache.put(key,y.getString(key,"")); }
    private void loadRecipes() {
        for(UUID oldId:new HashSet<>(recipes.keySet()))unregisterWorkbenchRecipe(oldId);
        recipes.clear(); File dir = new File(getDataFolder(), "custom"); File[] files = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files == null) return;
        for (File file : files) try {
            UUID id = UUID.fromString(file.getName().replaceFirst("(?i)\\.yml$", ""));
            YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
            ItemStack[] ingredients = new ItemStack[9];
            for (int i = 0; i < 9; i++) { ItemStack item = y.getItemStack("ingredients." + i); ingredients[i] = item == null || item.getType().isAir() ? null : item.clone(); }
            ItemStack result = y.getItemStack("result"); if (result != null && !result.getType().isAir()) recipes.put(id, new RecipeData(id, ingredients, result.clone()));
        } catch (Exception e) { getLogger().warning("略過無效配方檔 " + file.getName()); }
        for(RecipeData recipe:recipes.values())registerWorkbenchRecipe(recipe);
    }
    private NamespacedKey workbenchRecipeKey(UUID id){return new NamespacedKey(this,"custom_"+id.toString().replace("-",""));}
    private void unregisterWorkbenchRecipe(UUID id){Bukkit.removeRecipe(workbenchRecipeKey(id),true);}
    private boolean isPluginWorkbenchRecipe(Recipe recipe){return recipe instanceof Keyed keyed&&keyed.getKey().getNamespace().equalsIgnoreCase(getName().toLowerCase(Locale.ROOT))&&keyed.getKey().getKey().startsWith("custom_");}
    private void registerWorkbenchRecipe(RecipeData recipeData){
        unregisterWorkbenchRecipe(recipeData.id);
        int minRow=3,maxRow=-1,minCol=3,maxCol=-1;
        for(int i=0;i<9;i++)if(recipeData.ingredients[i]!=null){minRow=Math.min(minRow,i/3);maxRow=Math.max(maxRow,i/3);minCol=Math.min(minCol,i%3);maxCol=Math.max(maxCol,i%3);}
        if(maxRow<0)return;
        ShapedRecipe shaped=new ShapedRecipe(workbenchRecipeKey(recipeData.id),recipeData.result.clone());
        String[] rows=new String[maxRow-minRow+1];
        for(int row=minRow;row<=maxRow;row++){
            StringBuilder shapeRow=new StringBuilder();
            for(int col=minCol;col<=maxCol;col++){
                int index=row*3+col;ItemStack ingredient=recipeData.ingredients[index];
                if(ingredient==null)shapeRow.append(' ');
                else shapeRow.append((char)('A'+index));
            }
            rows[row-minRow]=shapeRow.toString();
        }
        shaped.shape(rows);
        for(int i=0;i<9;i++)if(recipeData.ingredients[i]!=null){ItemStack choice=recipeData.ingredients[i].clone();choice.setAmount(1);shaped.setIngredient((char)('A'+i),new RecipeChoice.ExactChoice(choice));}
        Bukkit.addRecipe(shaped);
    }
    private void flushIfDirty() {
        if (!dirty) return;
        dirty = false;
        Map<UUID, RecipeData> snapshot = new LinkedHashMap<>(recipes);
        Set<UUID> deleted = new HashSet<>(deletedRecipeIds);
        deletedRecipeIds.removeAll(deleted);
        io.execute(() -> {
            for (UUID id : deleted) {
                try { Files.deleteIfExists(new File(getDataFolder(), "custom/" + id + ".yml").toPath()); }
                catch (IOException e) { deletedRecipeIds.add(id); dirty = true; }
            }
            for (RecipeData recipe : snapshot.values()) {
                YamlConfiguration y = new YamlConfiguration();
                for (int i = 0; i < 9; i++) if (recipe.ingredients[i] != null) y.set("ingredients." + i, recipe.ingredients[i]);
                y.set("result", recipe.result);
                try { y.save(new File(getDataFolder(), "custom/" + recipe.id + ".yml")); } catch (IOException e) { getLogger().warning("配方保存失敗：" + recipe.id); dirty = true; }
            }
        });
    }
    private String msg(String key, Map<String,String> vars) {
        String value = messageCache.getOrDefault(key, key);
        for (var entry : vars.entrySet()) value = value.replace("%" + entry.getKey() + "%", entry.getValue());
        return ChatColor.translateAlternateColorCodes('&', value);
    }
    private String msg(String key) { return msg(key, Map.of()); }
    private void tell(CommandSender sender, String key) { sender.sendMessage(msg("prefix") + msg(key)); }
    private ItemStack named(Material material, String nameKey, String loreKey) {
        ItemStack item = new ItemStack(material); ItemMeta meta = item.getItemMeta(); meta.setDisplayName(msg(nameKey));
        if (loreKey != null) meta.setLore(List.of(msg(loreKey))); item.setItemMeta(meta); return item;
    }
    private void sign(Inventory inv, String kind) { ItemStack marker = new ItemStack(Material.PAPER); ItemMeta meta = marker.getItemMeta(); meta.getPersistentDataContainer().set(guiKey, PersistentDataType.STRING, kind); marker.setItemMeta(meta); inv.setItem(inv.getSize()-1, marker); }
    private boolean isSigned(Inventory inv) { if (!(inv.getHolder() instanceof GuiHolder)) return false; ItemStack marker = inv.getItem(inv.getSize()-1); return marker != null && marker.hasItemMeta() && marker.getItemMeta().getPersistentDataContainer().has(guiKey, PersistentDataType.STRING); }
    private void openEditor(Player player) { openEditor(player, null); }
    private void openEditor(Player player, RecipeData existing) {
        GuiHolder holder = new GuiHolder(this); Inventory inv = Bukkit.createInventory(holder, 54, msg("editor-title")); holder.inventory = inv; sign(inv, "editor");
        inv.setItem(45, named(Material.BARRIER,"cancel-name","cancel-lore")); inv.setItem(49, named(Material.EMERALD_BLOCK,"save-name","save-lore"));
        ItemStack glass = named(Material.GRAY_STAINED_GLASS_PANE,"ingredient-slots-name",null);
        for (int i=0;i<54;i++) if (i<45 && !isInputSlot(i)) inv.setItem(i, glass);
        EditorState state = new EditorState(existing == null ? null : existing.id);
        if (existing != null) {
            int[] slots={10,11,12,19,20,21,28,29,30};
            for(int i=0;i<9;i++) if(existing.ingredients[i]!=null) inv.setItem(slots[i],existing.ingredients[i].clone());
            inv.setItem(24,existing.result.clone());
        }
        editors.put(player.getUniqueId(),state); player.openInventory(inv);
    }
    private boolean isInputSlot(int slot) { return slot==10||slot==11||slot==12||slot==19||slot==20||slot==21||slot==28||slot==29||slot==30||slot==24; }
    private void saveEditor(Player player, Inventory inv) {
        ItemStack[] ingredients=new ItemStack[9]; int[] slots={10,11,12,19,20,21,28,29,30}; boolean any=false;
        for(int i=0;i<9;i++){ItemStack item=inv.getItem(slots[i]);if(item!=null&&!item.getType().isAir()){ingredients[i]=item.clone();any=true;}}
        ItemStack result=inv.getItem(24);
        if(!any||result==null||result.getType().isAir()){tell(player,"save-invalid");return;}
        EditorState state=editors.get(player.getUniqueId());
        UUID id=state!=null&&state.recipeId!=null?state.recipeId:UUID.randomUUID();
        RecipeData saved=new RecipeData(id,ingredients,result.clone()); recipes.put(id,saved); deletedRecipeIds.remove(id); registerWorkbenchRecipe(saved); dirty=true;
        editors.remove(player.getUniqueId()); player.sendMessage(msg("prefix")+msg("saved",Map.of("id",id.toString()))); openRecipe(player,saved);
    }
    private void openMenu(Player player, int page) {
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
    private void openRecipe(Player player, RecipeData recipe) {
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
            int raw=event.getRawSlot();
            if(raw>=top.getSize()) { if((event.getClick()!=ClickType.LEFT&&event.getClick()!=ClickType.RIGHT)||event.isShiftClick()||event.getHotbarButton()>=0) event.setCancelled(true); return; }
            if(raw==45){event.setCancelled(true);if(event.getClick()==ClickType.LEFT){editors.remove(player.getUniqueId());player.closeInventory();tell(player,"cancelled");}return;}
            if(raw==49){event.setCancelled(true);if(event.getClick()==ClickType.LEFT)saveEditor(player,top);return;}
            if(!isInputSlot(raw)||(event.getClick()!=ClickType.LEFT&&event.getClick()!=ClickType.RIGHT)||event.isShiftClick()||event.getHotbarButton()>=0) {event.setCancelled(true);return;}
            moveCursorIntoEditorSlot(event,player,top,raw);
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
            if(slot==11){if(recipes.remove(id)!=null){unregisterWorkbenchRecipe(id);deletedRecipeIds.add(id);dirty=true;player.sendMessage(msg("prefix")+msg("deleted"));}openMenu(player,0);}
            else if(slot==15)openMenu(player,0);
        }
    }
    private void moveCursorIntoEditorSlot(InventoryClickEvent event,Player player,Inventory top,int slot){
        event.setCancelled(true);
        ItemStack inSlot=event.getCurrentItem();if(inSlot!=null&&inSlot.getType().isAir())inSlot=null;
        ItemStack cursor=event.getView().getCursor();if(cursor!=null&&cursor.getType().isAir())cursor=null;
        boolean rightClick=event.getClick()==ClickType.RIGHT;
        ItemStack resultSlot,resultCursor;
        if(cursor==null){if(inSlot==null)return;if(rightClick){int take=(inSlot.getAmount()+1)/2;resultSlot=inSlot.clone();resultSlot.setAmount(inSlot.getAmount()-take);if(resultSlot.getAmount()==0)resultSlot=null;resultCursor=inSlot.clone();resultCursor.setAmount(take);}else{resultSlot=null;resultCursor=inSlot.clone();}}
        else if(inSlot==null){resultSlot=cursor.clone();if(rightClick){resultSlot.setAmount(1);resultCursor=cursor.clone();resultCursor.setAmount(cursor.getAmount()-1);if(resultCursor.getAmount()==0)resultCursor=null;}else resultCursor=null;}
        else if(inSlot.isSimilar(cursor)){
            int limit=Math.min(top.getMaxStackSize(),inSlot.getMaxStackSize());int move=Math.min(cursor.getAmount(),Math.max(0,limit-inSlot.getAmount()));
            if(rightClick)move=Math.min(move,1);
            if(move==0)return;resultSlot=inSlot.clone();resultSlot.setAmount(inSlot.getAmount()+move);resultCursor=cursor.clone();resultCursor.setAmount(cursor.getAmount()-move);if(resultCursor.getAmount()==0)resultCursor=null;
        }else{if(rightClick)return;resultSlot=cursor.clone();resultCursor=inSlot.clone();}
        event.setCurrentItem(resultSlot==null?null:resultSlot.clone());event.getView().setCursor(resultCursor==null?null:resultCursor.clone());
        ItemStack finalSlot=resultSlot==null?null:resultSlot.clone(),finalCursor=resultCursor==null?null:resultCursor.clone();
        Bukkit.getScheduler().runTask(this,()->{
            if(!player.isOnline()||player.getOpenInventory().getTopInventory()!=top||!isSigned(top))return;
            top.setItem(slot,finalSlot);player.setItemOnCursor(finalCursor);player.updateInventory();
        });
    }
    @EventHandler public void onDrag(InventoryDragEvent event) {
        Inventory top=event.getView().getTopInventory();if(!isSigned(top))return;
        String kind=top.getItem(top.getSize()-1).getItemMeta().getPersistentDataContainer().get(guiKey,PersistentDataType.STRING);
        if(!kind.equals("editor")||event.getRawSlots().stream().anyMatch(slot->slot<top.getSize()&&!isInputSlot(slot)))event.setCancelled(true);
    }
    @EventHandler public void onClose(InventoryCloseEvent event) { if(event.getPlayer() instanceof Player p && editors.remove(p.getUniqueId())!=null && event.getReason()!=InventoryCloseEvent.Reason.PLUGIN) { /* editor changes are intentionally not autosaved */ } }
    private void tryCraft(Player player, RecipeData recipe) {
        if(recipe==null)return;
        List<ItemStack> needed=new ArrayList<>();
        for(ItemStack ingredient:recipe.ingredients) if(ingredient!=null){int match=needed.stream().filter(i->i.isSimilar(ingredient)).findFirst().map(needed::indexOf).orElse(-1);if(match<0)needed.add(ingredient.clone());else needed.get(match).setAmount(needed.get(match).getAmount()+ingredient.getAmount());}
        for(ItemStack required:needed){int available=0;for(ItemStack item:player.getInventory().getStorageContents())if(item!=null&&item.isSimilar(required))available+=item.getAmount();if(available<required.getAmount()){tell(player,"ingredients-missing");return;}}
        for(ItemStack required:needed){int remain=required.getAmount();for(int i=0;i<player.getInventory().getStorageContents().length&&remain>0;i++){ItemStack item=player.getInventory().getItem(i);if(item!=null&&item.isSimilar(required)){int take=Math.min(remain,item.getAmount());item.setAmount(item.getAmount()-take);remain-=take;if(item.getAmount()==0)player.getInventory().setItem(i,null);}}}
        HashMap<Integer,ItemStack> overflow=player.getInventory().addItem(recipe.result.clone());for(ItemStack item:overflow.values())player.getWorld().dropItemNaturally(player.getLocation(),item);tell(player,"crafted");
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void onPrepareWorkbenchCraft(PrepareItemCraftEvent event) {
        CraftingInventory inventory=event.getInventory();ItemStack[] matrix=inventory.getMatrix();
        if(matrix.length!=9)return;
        RecipeData recipe=findWorkbenchRecipe(matrix);if(recipe!=null)inventory.setResult(recipe.result.clone());else if(isPluginWorkbenchRecipe(event.getRecipe()))inventory.setResult(null);
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onWorkbenchCraft(CraftItemEvent event) {
        if(!(event.getWhoClicked() instanceof Player player))return;
        CraftingInventory inventory=event.getInventory();ItemStack[] matrix=inventory.getMatrix();if(matrix.length!=9)return;
        RecipeData recipe=findWorkbenchRecipe(matrix);if(recipe==null&&!isPluginWorkbenchRecipe(event.getRecipe()))return;
        event.setCancelled(true);
        if(recipe==null)return;
        if(event.isShiftClick()||(event.getClick()!=ClickType.LEFT&&event.getClick()!=ClickType.RIGHT))return;
        if(!hasInventoryRoom(player,recipe.result)){tell(player,"inventory-full");return;}
        for(int i=0;i<9;i++){
            ItemStack required=recipe.ingredients[i];if(required==null)continue;
            ItemStack present=matrix[i];int remaining=present.getAmount()-required.getAmount();
            if(remaining<=0)matrix[i]=null;else{ItemStack leftover=present.clone();leftover.setAmount(remaining);matrix[i]=leftover;}
        }
        inventory.setMatrix(matrix);
        RecipeData next=findWorkbenchRecipe(matrix);inventory.setResult(next==null?null:next.result.clone());
        Map<Integer,ItemStack> overflow=player.getInventory().addItem(recipe.result.clone());
        for(ItemStack item:overflow.values())player.getWorld().dropItemNaturally(player.getLocation(),item);
        player.updateInventory();
    }
    private RecipeData findWorkbenchRecipe(ItemStack[] matrix) {
        if(matrix.length!=9)return null;
        for(RecipeData recipe:recipes.values()){
            int minRow=3,maxRow=-1,minCol=3,maxCol=-1;
            for(int i=0;i<9;i++)if(recipe.ingredients[i]!=null){minRow=Math.min(minRow,i/3);maxRow=Math.max(maxRow,i/3);minCol=Math.min(minCol,i%3);maxCol=Math.max(maxCol,i%3);}
            if(maxRow<0)continue;
            int height=maxRow-minRow+1,width=maxCol-minCol+1;
            for(int offsetRow=0;offsetRow<=3-height;offsetRow++)for(int offsetCol=0;offsetCol<=3-width;offsetCol++)for(int mirror=0;mirror<2;mirror++){
                boolean matches=true;
                for(int row=0;row<3&&matches;row++)for(int col=0;col<3;col++){
                    int patternRow=row-offsetRow,patternCol=col-offsetCol;ItemStack required=null;
                    if(patternRow>=0&&patternRow<height&&patternCol>=0&&patternCol<width){if(mirror==1)patternCol=width-1-patternCol;required=recipe.ingredients[(minRow+patternRow)*3+minCol+patternCol];}
                    ItemStack present=matrix[row*3+col];
                    if(required==null){if(present!=null&&!present.getType().isAir()){matches=false;break;}}
                    else if(present==null||present.getType().isAir()||!present.isSimilar(required)||present.getAmount()<required.getAmount()){matches=false;break;}
                }
                if(matches)return recipe;
            }
        }
        return null;
    }
    private boolean hasInventoryRoom(Player player,ItemStack result) {
        int remaining=result.getAmount(),limit=Math.min(player.getInventory().getMaxStackSize(),result.getMaxStackSize());
        for(ItemStack item:player.getInventory().getStorageContents())if(item!=null&&item.isSimilar(result))remaining-=Math.max(0,limit-item.getAmount());
        for(ItemStack item:player.getInventory().getStorageContents())if(item==null||item.getType().isAir())remaining-=limit;
        return remaining<=0;
    }

    private final class CraftCommand implements TabExecutor {
        public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
            if(args.length==0){if(!(sender instanceof Player p)){tell(sender,"player-only");return true;}if(!p.hasPermission(getConfig().getString("permissions.use","catrixcustomcrafting.use"))){tell(p,"no-permission");return true;}openMenu(p,0);return true;} String sub=args[0].toLowerCase(Locale.ROOT);
            if(sub.equals("reload")){if(!sender.hasPermission(getConfig().getString("permissions.reload","catrixcustomcrafting.reload"))){tell(sender,"no-permission");return true;}mergeResource("config.yml");mergeResource("messages.yml");reloadConfig();loadMessages();loadRecipes();tell(sender,"reload");return true;}
            if(sub.equals("create")){if(!(sender instanceof Player p)){tell(sender,"player-only");return true;}if(!p.hasPermission(getConfig().getString("permissions.create","catrixcustomcrafting.create"))){tell(p,"no-permission");return true;}openEditor(p);tell(p,"create-start");return true;}
            if(sub.equals("edit")){if(!(sender instanceof Player p)){tell(sender,"player-only");return true;}if(!p.hasPermission(getConfig().getString("permissions.create","catrixcustomcrafting.create"))){tell(p,"no-permission");return true;}if(args.length<2){tell(p,"usage");return true;}try{RecipeData recipe=recipes.get(UUID.fromString(args[1]));if(recipe==null){tell(p,"recipe-missing");return true;}openEditor(p,recipe);}catch(IllegalArgumentException e){tell(p,"recipe-missing");}return true;}
            if(sub.equals("list")){if(!sender.hasPermission(getConfig().getString("permissions.use","catrixcustomcrafting.use"))){tell(sender,"no-permission");return true;}if(recipes.isEmpty()){tell(sender,"recipe-empty");return true;}tell(sender,"recipe-list");sender.sendMessage(recipes.keySet().stream().map(UUID::toString).reduce((a,b)->a+", "+b).orElse(""));return true;}
            if(sub.equals("open")){if(!(sender instanceof Player p)){tell(sender,"player-only");return true;}if(!p.hasPermission(getConfig().getString("permissions.use","catrixcustomcrafting.use"))){tell(p,"no-permission");return true;}if(args.length<2){tell(p,"usage");return true;}try{RecipeData recipe=recipes.get(UUID.fromString(args[1]));if(recipe==null){tell(p,"recipe-missing");return true;}openRecipe(p,recipe);}catch(IllegalArgumentException e){tell(p,"recipe-missing");}return true;}
            tell(sender,"usage");return true;
        }
        public List<String> onTabComplete(CommandSender s,Command c,String a,String[] args){if(args.length==1)return Arrays.stream(new String[]{"create","edit","list","open","reload"}).filter(x->x.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();if(args.length==2&&(args[0].equalsIgnoreCase("open")||args[0].equalsIgnoreCase("edit")))return recipes.keySet().stream().map(UUID::toString).filter(x->x.startsWith(args[1])).toList();return List.of();}
    }
    private final class VersionCommand implements TabExecutor {
        public boolean onCommand(CommandSender sender,Command command,String label,String[] args){if(!sender.hasPermission(getConfig().getString("permissions.version","catrixcustomcrafting.version"))){tell(sender,"no-permission");return true;}if(System.currentTimeMillis()-checkedAt<600_000L){if(updateState.known())sendVersion(sender);else tell(sender,"update-checking");return true;}tell(sender,"update-checking");checkUpdate(true);return true;}
        public List<String> onTabComplete(CommandSender s,Command c,String a,String[] args){return List.of();}
    }
    private void checkUpdate(boolean manual){
        checkedAt=System.currentTimeMillis(); String repo=getConfig().getString("settings.github-repository","me-cat6666/CatrixCustomCrafting");
        io.execute(()->{try{HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();HttpRequest req=HttpRequest.newBuilder(URI.create("https://api.github.com/repos/"+repo+"/releases/latest")).timeout(Duration.ofSeconds(8)).header("Accept","application/vnd.github+json").header("User-Agent","CatrixCustomCrafting").GET().build();HttpResponse<String> response=client.send(req,HttpResponse.BodyHandlers.ofString());if(response.statusCode()!=200)throw new IOException("HTTP "+response.statusCode());String tag=response.body().replaceAll("(?s).*?\\\"tag_name\\\"\\s*:\\s*\\\"([^\\\"]+).*","$1");if(tag.equals(response.body()))throw new IOException("tag not found");latestVersion=tag;updateState=compare(tag,pluginVersion);Bukkit.getScheduler().runTask(this,()->{if(updateState==UpdateState.AVAILABLE)getLogger().info("發現新版本 "+tag+"（目前："+pluginVersion+"）");else if(updateState==UpdateState.DEVELOPMENT)getLogger().info("目前版本比 GitHub 新（開發版）："+pluginVersion);else getLogger().info("目前已是最新版本："+tag);if(manual)Bukkit.getOnlinePlayers().stream().filter(p->p.hasPermission(getConfig().getString("permissions.version","catrixcustomcrafting.version"))).forEach(this::sendVersion);});}catch(Exception e){updateState=UpdateState.FAILED;getLogger().warning("GitHub 版本檢查失敗："+e.getMessage());}});
    }
    private UpdateState compare(String remote,String current){int c=compareVersions(normalize(current),normalize(remote));return c<0?UpdateState.AVAILABLE:c>0?UpdateState.DEVELOPMENT:UpdateState.CURRENT;}
    private String normalize(String version){return version.trim().replaceFirst("(?i)^v","").split("[-+]")[0];}
    private int compareVersions(String a,String b){try{String[] x=a.split("\\."),y=b.split("\\.");for(int i=0;i<Math.max(x.length,y.length);i++){int p=i<x.length?Integer.parseInt(x[i]):0,q=i<y.length?Integer.parseInt(y[i]):0;if(p!=q)return Integer.compare(p,q);}return 0;}catch(NumberFormatException e){return a.compareToIgnoreCase(b);}}
    private void sendVersion(Player p){if(updateState==UpdateState.AVAILABLE)p.sendMessage(msg("prefix")+msg("update-available",Map.of("version",latestVersion,"current",pluginVersion)));else if(updateState==UpdateState.DEVELOPMENT)p.sendMessage(msg("prefix")+msg("update-dev",Map.of("current",pluginVersion)));else if(updateState==UpdateState.CURRENT)p.sendMessage(msg("prefix")+msg("update-current",Map.of("version",latestVersion)));}
    private void sendVersion(CommandSender sender){if(sender instanceof Player player){sendVersion(player);return;}if(updateState==UpdateState.AVAILABLE)sender.sendMessage(msg("update-console-available",Map.of("version",latestVersion,"current",pluginVersion)));else if(updateState==UpdateState.DEVELOPMENT)sender.sendMessage(msg("update-console-dev",Map.of("current",pluginVersion)));else if(updateState==UpdateState.CURRENT)sender.sendMessage(msg("update-console-current",Map.of("version",latestVersion)));}
    @EventHandler public void onJoin(org.bukkit.event.player.PlayerJoinEvent event){Player player=event.getPlayer();Bukkit.getScheduler().runTaskLater(this,()->{if(player.isOnline()&&player.hasPermission(getConfig().getString("permissions.version","catrixcustomcrafting.version"))&&updateState==UpdateState.AVAILABLE)sendVersion(player);},60L);}
    private enum UpdateState { UNKNOWN, CURRENT, AVAILABLE, DEVELOPMENT, FAILED; boolean known(){return this!=UNKNOWN&&this!=FAILED;} static UpdateState unknown(){return UNKNOWN;} }
    private record RecipeData(UUID id,ItemStack[] ingredients,ItemStack result){}
    private static final class EditorState{ final UUID recipeId; EditorState(UUID recipeId){this.recipeId=recipeId;} }
    private static final class GuiHolder implements InventoryHolder { final CatrixCustomCrafting plugin; Inventory inventory; GuiHolder(CatrixCustomCrafting plugin){this.plugin=plugin;} public Inventory getInventory(){return inventory;} }
}
