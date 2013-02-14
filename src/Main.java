

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jsonjedi.JSONSchema;
import jsonjedi.JSONSchemaBuilder;

import static java.lang.invoke.MethodHandles.*;

public class Main {
  static class User {
    String firstName;
    long age;
    
    List<Address> addresses;
    Phone phone;

    @Override
    public String toString() {
      return "User " + firstName + " " + age + " " + phone + " " + addresses;
    }
  }

  static class Address {
    String streetAddress;

    @Override
    public String toString() {
      return "Address " + streetAddress;
    }
  }
  
  static class Phone {
    String number;
    String type;
    
    @Override
    public String toString() {
      return "Phone " + number + " " + type;
    }
  }

  public static void main(String[] args) throws IOException {
    JSONSchema<User> schema = JSONSchemaBuilder.schema(lookup(), User.class, builder -> {
      builder.
        entry("address", Address.class, (user, stream) -> {
          user.addresses = stream.collect(Collectors.toList());
        }).
        entry("phoneNumber", Phone.class, (user, stream) -> {
          //stream.forEach(System.out::println);
          user.phone = stream.filter(phone -> phone.type.equals("home")).findFirst().get();
        });
    });

    Path path = Paths.get("sample.json");
    try(BufferedReader reader = Files.newBufferedReader(path, Charset.defaultCharset())) {
      Stream<User> stream = schema.stream(reader);
      stream.filter(user -> user.firstName.length() > 3).forEach(System.out::println);
    }
  }
}
