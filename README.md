jsonjedi
========

parsing JSON with JDK8 lambdas


    class User {
      String firstName;
      long age;
    
      List<Address> addresses;
      Phone phone;
    }
    class Address {
      String streetAddress;

      @Override
      public String toString() {
        return "Address " + streetAddress;
      }
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

