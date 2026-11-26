package dev.skycodex;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.Blocks;
import java.util.*;

/** Server-paced vanilla slot operations; never writes directly to inventories. */
final class CraftTask {
    private final Controller c;
    private final Minecraft mc;
    private final CraftingRecipe recipe;
    private final ItemStack expected;
    private int remaining,delay,phase,gridSize,gridWidth,containerId=-1,actionIndex,outputWait;
    private AbstractContainerMenu menu;
    private final List<int[]> actions=new ArrayList<>();
    private List<Ingredient> gridIngredients;
    private ItemStack taken;
    private int beforeTake;
    private boolean opening;

    CraftTask(Controller controller,CraftingRecipe recipe,int times) {
        c=controller;mc=c.mc;this.recipe=recipe;remaining=times;expected=recipe.getResultItem(mc.level.registryAccess());
        if(expected.isEmpty())throw new IllegalArgumentException("Recipe has no standard output");
    }
    boolean tick() {
        if(delay>0){delay--;return false;}
        if(phase==0){prepare();return false;}
        if(mc.player.containerMenu!=menu||menu.containerId!=containerId)throw new IllegalStateException("Crafting container changed");
        switch(phase) {
            case 1 -> {
                if(!menu.getCarried().isEmpty())throw new IllegalStateException("Mouse cursor holds an item; stop and put it down");
                for(int i=1;i<=gridSize;i++)if(!menu.getSlot(i).getItem().isEmpty()){
                    click(i,0,ClickType.QUICK_MOVE);if(++outputWait>30)throw new IllegalStateException("Cannot clear crafting grid; inventory may be full");return false;
                }
                outputWait=0;plan();phase=2;
            }
            case 2 -> {
                if(actionIndex<actions.size()){int[] a=actions.get(actionIndex++);click(a[0],a[1],ClickType.PICKUP);}
                else {phase=3;outputWait=0;delay=6;}
            }
            case 3 -> {
                if(!menu.getCarried().isEmpty())throw new IllegalStateException("Unexpected cursor item after arranging recipe; inspect inventory");
                for(int i=0;i<gridIngredients.size();i++){
                    Ingredient ingredient=gridIngredients.get(i);ItemStack s=menu.getSlot(i+1).getItem();
                    if(ingredient.isEmpty()? !s.isEmpty():!ingredient.test(s))throw new IllegalStateException("Server crafting grid differs from planned ingredients");
                }
                ItemStack result=menu.getSlot(0).getItem();
                if(result.isEmpty()){if(++outputWait>30)throw new IllegalStateException("Server did not produce crafting output; recipe may be server-only or disabled");delay=4;return false;}
                if(!ItemStack.isSameItemSameComponents(result,expected)||result.getCount()!=expected.getCount())throw new IllegalStateException("Server recipe output differs; refusing to take unexpected item");
                taken=result.copy();beforeTake=countExact(taken);
                click(0,0,ClickType.QUICK_MOVE);phase=4;outputWait=0;
            }
            case 4 -> {
                if(countExact(taken)<beforeTake+taken.getCount()){
                    if(++outputWait>30)throw new IllegalStateException("Craft output was not added to inventory; inventory full or server rejected operation");delay=4;return false;
                }
                if(--remaining==0)return true;phase=1;outputWait=0;
            }
        }
        return false;
    }
    private void prepare() {
        boolean needsTable=recipe instanceof ShapedRecipe s?(s.getWidth()>2||s.getHeight()>2):recipe.getIngredients().size()>4;
        if(mc.player.containerMenu instanceof CraftingMenu){gridSize=9;gridWidth=3;}
        else if(mc.player.containerMenu==mc.player.inventoryMenu && !needsTable){gridSize=4;gridWidth=2;}
        else if(mc.player.containerMenu==mc.player.inventoryMenu && needsTable){
            if(opening)throw new IllegalStateException("Crafting table did not open");
            BlockPos center=mc.player.blockPosition(),closest=null;double best=Double.MAX_VALUE;
            for(BlockPos p:BlockPos.betweenClosed(center.offset(-4,-3,-4),center.offset(4,3,4)))if(mc.level.getBlockState(p).is(Blocks.CRAFTING_TABLE)){
                double d=p.distSqr(center);if(d<best){best=d;closest=p.immutable();}}
            if(closest==null)throw new IllegalStateException("A nearby reachable crafting table is required; place one or move closer first");
            c.useBlock(closest);opening=true;delay=20;return;
        }else throw new IllegalStateException("Close the current container; only inventory or crafting table is supported");
        menu=mc.player.containerMenu;containerId=menu.containerId;phase=1;
    }
    /** True when this task opened the crafting table itself, so it should close it again afterwards. */
    boolean openedTable(){return opening;}
    private int countExact(ItemStack target){int n=0;for(ItemStack s:mc.player.getInventory().items)if(ItemStack.isSameItemSameComponents(s,target))n+=s.getCount();return n;}
    private void click(int slot,int button,ClickType type){mc.gameMode.handleInventoryMouseClick(containerId,slot,button,type,mc.player);delay=5;}
    private void plan() {
        gridIngredients=new ArrayList<>(Collections.nCopies(gridSize,Ingredient.EMPTY));
        var inputs=recipe.getIngredients();
        if(recipe instanceof ShapedRecipe s){for(int y=0;y<s.getHeight();y++)for(int x=0;x<s.getWidth();x++)gridIngredients.set(y*gridWidth+x,inputs.get(y*s.getWidth()+x));}
        else for(int i=0;i<inputs.size();i++)gridIngredients.set(i,inputs.get(i));
        List<Integer> targets=new ArrayList<>();List<Set<Integer>> choices=new ArrayList<>();int[] capacities=new int[menu.slots.size()];
        for(int i=0;i<menu.slots.size();i++){Slot slot=menu.getSlot(i);if(slot.container==mc.player.getInventory() && slot.getContainerSlot()<36)capacities[i]=slot.getItem().getCount();}
        for(int i=0;i<gridIngredients.size();i++)if(!gridIngredients.get(i).isEmpty()){
            Set<Integer> options=new LinkedHashSet<>();for(int j=0;j<capacities.length;j++)if(capacities[j]>0&&gridIngredients.get(i).test(menu.getSlot(j).getItem()))options.add(j);
            choices.add(options);targets.add(i+1);
        }
        int[] sources=IngredientAllocator.allocate(choices,capacities);actions.clear();actionIndex=0;
        for(int i=0;i<sources.length;i++){
            int src=sources[i];actions.add(new int[]{src,0});actions.add(new int[]{targets.get(i),1});
            if(capacities[src]>1)actions.add(new int[]{src,0});capacities[src]--;
        }
    }
}
