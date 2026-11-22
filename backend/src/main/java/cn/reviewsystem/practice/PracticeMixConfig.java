package cn.reviewsystem.practice;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record PracticeMixConfig(String version,double bank,double ai) {
 public PracticeMixConfig(@Value("${app.practice.mix.version:v3}") String version,@Value("${app.practice.mix.bank:0.7}") double bank,@Value("${app.practice.mix.ai:0.3}") double ai){this.version=version;this.bank=bank;this.ai=ai;if(bank<0||ai<0||Math.abs(bank+ai-1.0)>0.000001)throw new IllegalArgumentException("Practice mix ratios must add up to 1");}
}
