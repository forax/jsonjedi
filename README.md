jsonjedi
========

parsing JSON with JDK8 lambdas

With this small library, I tried to see how to use lambdas when designing an API.
It works in two steps, first with a `JSONSchemaBuilder` create a schema that describes
which part of the JSON must be parsed into objects.
Then on a `JSONSchema`, the method stream turn a schema and a Reader to a stream of objects.

A small example:
`````java
    class User {
      String firstName;
      long age;
    
      List<Address> addresses;
      Phone phone;
    }
    class Address {
      String streetAddress;
    }
  
    class Phone {
      String number;
      String type;
    }

    public static void main(String[] args) throws IOException {
      // creates a Schema
      JSONSchema<User> schema = JSONSchemaBuilder.schema(lookup(), User.class, builder -> {
        builder.
          entry("address", Address.class, (user, stream) -> {
            user.addresses = stream.collect(Collectors.toList());
          }).
          entry("phoneNumber", Phone.class, (user, stream) -> {
            user.phone = stream.filter(phone -> phone.type.equals("home")).findFirst().get();
          });
      });

      // parse a JSON file
      Path path = Paths.get("sample.json");
      try(BufferedReader reader = Files.newBufferedReader(path, Charset.defaultCharset())) {
        Stream<User> stream = schema.stream(reader);
        stream.filter(user -> user.firstName.length() > 3).forEach(System.out::println);
      }
    }
`````
