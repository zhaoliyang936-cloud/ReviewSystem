package cn.reviewsystem.question;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="app.import.enabled",havingValue="true")
public class QuestionImportRunner implements ApplicationRunner {
 private final QuestionImportService service;private final ConfigurableApplicationContext context;private final String manifest;private final String report;
 public QuestionImportRunner(QuestionImportService service,ConfigurableApplicationContext context,@Value("${app.import.manifest}") String manifest,@Value("${app.import.report:import-report.json}") String report){this.service=service;this.context=context;this.manifest=manifest;this.report=report;}
 @Override public void run(ApplicationArguments args)throws Exception {var result=service.importFile(Path.of(manifest),Path.of(report));int code=result.successful()?0:1;SpringApplication.exit(context,()->code);System.exit(code);}
}
