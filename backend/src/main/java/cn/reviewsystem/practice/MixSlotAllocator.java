package cn.reviewsystem.practice;

import java.util.List;
import java.util.stream.IntStream;

public final class MixSlotAllocator {
 private MixSlotAllocator(){}
 public record Slots(int aiSlots,List<Boolean> aiPositions){public int bankSlots(){return aiPositions.size()-aiSlots;}}
 public static Slots allocate(int n,double aiRatio){
  if(n<0||aiRatio<0||aiRatio>1)throw new IllegalArgumentException("Invalid mix slots");
  int ai=(int)Math.round(n*aiRatio);boolean[] positions=new boolean[n];
  for(int j=0;j<ai;j++)positions[(int)Math.floor((j+0.5)*n/ai)]=true;
  return new Slots(ai,IntStream.range(0,n).mapToObj(i->positions[i]).toList());
 }
}
