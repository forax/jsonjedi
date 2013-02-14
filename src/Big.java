import static java.lang.invoke.MethodHandles.lookup;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jsonjedi.JSONSchema;
import jsonjedi.JSONSchemaBuilder;

public class Big {
  static class WebApps {
    WebApp webApp;
  }
  
  static class WebApp {
    List<Servlet> servlets;
  }

  static class Servlet {
    String name;
    String templatePath;
  }
  
  static class InitParam {
    String templatePath;
  }
  
  public static void main(String[] args) throws IOException {
    JSONSchema<WebApps> schema = JSONSchemaBuilder.schema(lookup(), WebApps.class, builder -> {
      builder.disallowImplicit().
        entry("web-app", WebApp.class, builder2 -> {
            builder2.entry("servlet", Servlet.class, builder3 -> {
                builder3.disallowImplicit().
                  value("servlet-name", String.class, (servlet, name) -> servlet.name = name).
                  entry("init-param", InitParam.class, (servlet, stream) -> {
                    stream.findFirst().ifPresent(initParam -> servlet.templatePath = initParam.templatePath);
                  });  
            },
            (webApp, stream) -> {
              webApp.servlets = stream.collect(Collectors.toList());
            });
        },
        (webApps, stream) -> {
          webApps.webApp = stream.findFirst().get();
        });
    });

    Path path = Paths.get("big.json");
    try(BufferedReader reader = Files.newBufferedReader(path, Charset.defaultCharset())) {
      WebApps webApps = schema.stream(reader).findFirst().get();
      webApps.webApp.servlets.stream().
        map(servlet -> servlet.templatePath).filter(path2 -> path2 != null).
        forEach(System.out::println);
    }
  }
}
