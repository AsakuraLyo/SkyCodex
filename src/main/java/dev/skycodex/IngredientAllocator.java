package dev.skycodex;

import java.util.*;

/** Unit-demand ingredient matching with shared stack capacities (augmenting paths). */
final class IngredientAllocator {
    static int[] allocate(List<Set<Integer>> choices, int[] capacities) {
        int[] result=new int[choices.size()];Arrays.fill(result,-1);
        List<List<Integer>> owners=new ArrayList<>();for(int ignored:capacities)owners.add(new ArrayList<>());
        for(int i=0;i<choices.size();i++)if(!assign(i,choices,capacities,owners,result,new boolean[choices.size()],new boolean[capacities.length]))throw new IllegalArgumentException("Missing crafting ingredients");
        return result;
    }
    private static boolean assign(int ingredient,List<Set<Integer>> choices,int[] cap,List<List<Integer>> owners,int[] result,boolean[] seenIngredients,boolean[] seenSources) {
        if(seenIngredients[ingredient])return false;seenIngredients[ingredient]=true;
        for(int source:choices.get(ingredient)) {
            if(source<0||source>=cap.length||seenSources[source]||cap[source]<=0)continue;seenSources[source]=true;
            if(owners.get(source).size()<cap[source]){owners.get(source).add(ingredient);result[ingredient]=source;return true;}
            for(int previous:new ArrayList<>(owners.get(source)))if(assign(previous,choices,cap,owners,result,seenIngredients,seenSources)){
                owners.get(source).remove(Integer.valueOf(previous));owners.get(source).add(ingredient);result[ingredient]=source;return true;
            }
        }
        return false;
    }
}
