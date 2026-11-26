package dev.skycodex;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class IngredientAllocatorTest {
    @Test void reallocatesBroadIngredientToPreserveSpecificOne(){
        int[] r=IngredientAllocator.allocate(List.of(new LinkedHashSet<>(List.of(0,1)),Set.of(0)),new int[]{1,1});
        assertArrayEquals(new int[]{1,0},r);
    }
    @Test void respectsSharedStackCapacity(){
        int[] r=IngredientAllocator.allocate(List.of(Set.of(0),Set.of(0),Set.of(0,1)),new int[]{2,1});
        assertArrayEquals(new int[]{0,0,1},r);
    }
    @Test void refusesInsufficientMaterials(){assertThrows(IllegalArgumentException.class,()->IngredientAllocator.allocate(List.of(Set.of(0),Set.of(0)),new int[]{1}));}
    @Test void acceptsEmptyGrid(){assertEquals(0,IngredientAllocator.allocate(List.of(),new int[0]).length);}
}
