package cn.reviewsystem.practice;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MixSlotAllocatorTest {
 @Test void tenSlotsAreUniform(){var slots=MixSlotAllocator.allocate(10,0.3);assertEquals(3,slots.aiSlots());assertEquals(7,slots.bankSlots());assertEquals(List.of(1,5,8),positions(slots));assertEquals(slots,MixSlotAllocator.allocate(10,0.3));}
 @Test void boundaries(){assertEquals(List.of(1),positions(MixSlotAllocator.allocate(3,0.3)));assertEquals(List.of(1,3),positions(MixSlotAllocator.allocate(5,0.3)));assertTrue(positions(MixSlotAllocator.allocate(1,0.3)).isEmpty());}
 private List<Integer> positions(MixSlotAllocator.Slots slots){List<Integer> out=new ArrayList<>();for(int i=0;i<slots.aiPositions().size();i++)if(slots.aiPositions().get(i))out.add(i);return out;}
}
