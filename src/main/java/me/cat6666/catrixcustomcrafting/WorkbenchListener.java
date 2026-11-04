package me.cat6666.catrixcustomcrafting;

import me.cat6666.catrixcustomcrafting.data.RecipeData;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;

import java.util.*;

final class WorkbenchListener implements Listener {
    private final CatrixCustomCrafting plugin;

    WorkbenchListener(CatrixCustomCrafting plugin) {
        this.plugin = plugin;
    }

    private NamespacedKey workbenchRecipeKey(UUID id){return new NamespacedKey(plugin,"custom_"+id.toString().replace("-",""));}
    void unregisterWorkbenchRecipe(UUID id){Bukkit.removeRecipe(workbenchRecipeKey(id),true);}
    private boolean isPluginWorkbenchRecipe(Recipe recipe){return recipe instanceof Keyed keyed&&keyed.getKey().getNamespace().equalsIgnoreCase(plugin.getName().toLowerCase(Locale.ROOT))&&keyed.getKey().getKey().startsWith("custom_");}
    void registerWorkbenchRecipe(RecipeData recipeData){
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
        for(int i=0;i<9;i++)if(recipeData.ingredients[i]!=null){ItemStack choice=recipeData.ingredients[i].clone();choice.setAmount(1);shaped.setIngredient((char)('A'+i),recipeData.exactMatch?new RecipeChoice.ExactChoice(choice):new RecipeChoice.MaterialChoice(choice.getType()));}
        Bukkit.addRecipe(shaped);
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
        if(!player.hasPermission(plugin.getConfig().getString("permissions.use","catrixcustomcrafting.use"))){plugin.tell(player,"no-permission");return;}
        if(event.isShiftClick()||(event.getClick()!=ClickType.LEFT&&event.getClick()!=ClickType.RIGHT))return;
        if(!hasInventoryRoom(player,recipe.result)){plugin.tell(player,"inventory-full");return;}
        for(int i=0;i<9;i++){
            ItemStack present=matrix[i];
            if (present == null || present.getType().isAir()) continue;
            int remaining=present.getAmount()-1;
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
        for(RecipeData recipe:plugin.recipes()){
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
                    else if(present==null||present.getType().isAir()||!ingredientMatches(present,required,recipe.exactMatch)||present.getAmount()<required.getAmount()){matches=false;break;}
                }
                if(matches)return recipe;
            }
        }
        return null;
    }
    private boolean ingredientMatches(ItemStack actual,ItemStack expected,boolean exact){return actual!=null&&expected!=null&&(exact?actual.isSimilar(expected):actual.getType()==expected.getType());}
    private boolean hasInventoryRoom(Player player,ItemStack result) {
        int remaining=result.getAmount(),limit=Math.min(player.getInventory().getMaxStackSize(),result.getMaxStackSize());
        for(ItemStack item:player.getInventory().getStorageContents())if(item!=null&&item.isSimilar(result))remaining-=Math.max(0,limit-item.getAmount());
        for(ItemStack item:player.getInventory().getStorageContents())if(item==null||item.getType().isAir())remaining-=limit;
        return remaining<=0;
    }

}
