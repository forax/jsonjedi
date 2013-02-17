package jsonjedi;

import static java.lang.invoke.MethodType.*;
import static java.lang.invoke.MethodHandles.*;

import java.io.BufferedReader;
import java.io.IOError;
import java.io.IOException;
import java.io.Reader;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Spliterator;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.Streams;

import org.json.simple.parser.ContentHandler;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

/**
 * Build a structured description (schema) of a JSON.
 *
 * @param <T> type of the object that will be parsed.
 * 
 * @see #schema(Lookup, Class, Consumer)
 */
public class JSONSchemaBuilder<T> {
  /**
   * Consumer that takes an element of type T and an int value.
   *
   * @param <T> type of the element.
   * 
   * @see BiConsumer
   * @see JSONSchemaBuilder#value(String, BiIntValueConsumer)
   */
  public interface BiIntValueConsumer<T> {
    void accept(T element, int value);
  }
  
  /**
   * Consumer that takes an element of type T and a long value.
   *
   * @param <T> type of the element.
   * 
   * @see BiConsumer
   * @see JSONSchemaBuilder#value(String, BiLongValueConsumer)
   */
  public interface BiLongValueConsumer<T> {
    void accept(T element, long value);
  }
  
  /**
   * Consumer that takes an element of type T and a double value.
   *
   * @param <T> type of the element.
   * 
   * @see BiConsumer
   * @see JSONSchemaBuilder#value(String, BiDoubleValueConsumer)
   */
  public interface BiDoubleValueConsumer<T> {
    void accept(T element, double value);
  }

  private final Lookup lookup;
  private final Class<T> type;
  final MethodHandle constructor;
  final HashMap<String, Object> ruleMap = new HashMap<>();  
  private final BiConsumer<Object, Stream<T>> streamConsumer;
  private boolean allowImplicit = true;

  JSONSchemaBuilder(Lookup lookup, Class<T> type, BiConsumer<Object, Stream<T>> streamConsumer) {
    this.lookup = lookup;
    this.type = type;
    this.constructor = getConstructor(lookup, type);
    this.streamConsumer = streamConsumer;
  }

  // Context's state of the JSON handler
  enum ContextState {
    NONE,
    START_STREAM,
    END_STREAM,
    PUBLISH,
    DISCARD
  }
  
  static class Context {
    final JSONSchemaBuilder<?> builder;
    Object object;
    ContextState state;

    Context(JSONSchemaBuilder<?> builder, ContextState state) {
      this.builder = builder;
      this.state = state;
    }
    
    @Override
    public String toString() {
      return super.toString() + " " + builder.toString() + " " + Objects.toString(object) +
          " state: " + state;
    }
  }

  // this context is used to tell the JSON Handler to discard
  static final Context DISCARD_CONTEXT = new Context(null, ContextState.DISCARD);

  static class JSONSpliterator<T> implements Spliterator<T> {
    private final Handler handler;
    private final Context context;

    JSONSpliterator(Handler handler, Context context) {
      this.handler = handler;
      this.context = context;
    }

    @Override
    public Spliterator<T> trySplit() {
      return null;
    }
    @Override
    public boolean hasExactSplits() {
      return false;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean tryAdvance(Consumer<? super T> consumer) {
      do {
        try {
          handler.push();
        } catch (IOException | ParseException e) {
          throw new IOError(e);
        }

        //System.out.println("push " + context + " " + context.object + " " + context.state);

        if (context.state == ContextState.END_STREAM) {
          return false;
        }  
      } while(context.state != ContextState.PUBLISH);
      
      consumer.accept((T)context.object);
      context.state = ContextState.NONE;
      return true;
    }

    @Override
    public void forEach(Consumer<? super T> consumer) {
      // this code is duplicated just to be faster
      do {
        // nothing
      } while(tryAdvance(consumer));
    }
  }

  Stream<T> createAStream(Handler handler, Context context) {
    return Streams.stream(new JSONSpliterator<T>(handler, context), Streams.STREAM_IS_DISTINCT);
  }
  
  void createAndSendStream(Handler handler, Object object, Context context) {
    streamConsumer.accept(object, createAStream(handler, context));
  }

  static class Handler implements ContentHandler {
    private final JSONParser parser;
    private final Reader reader;

    private final ArrayDeque<Context> contextStack = new ArrayDeque<>();
    private MethodHandle valueSetter;

    Handler(JSONParser parser, Reader reader) {
      this.parser = parser;
      this.reader = reader;
    }

    
    Context createFirstContext(JSONSchemaBuilder<?> builder) {
      Context context = new Context(builder, ContextState.NONE);
      contextStack.push(context);
      return context;
    }

    void push() throws IOException, ParseException {
      parser.parse(reader, this, true);
      
      // spin a stream ??
      Context context = contextStack.peek();
      if (context.state == ContextState.START_STREAM) {
        Object object = context.object;
        context.object = null;
        context.state = ContextState.NONE;
        context.builder.createAndSendStream(this, object, context);
        
        // discard until context is popped, but object is still valid
        context.state = ContextState.DISCARD;
      }
    }


    @Override
    public void startJSON() throws ParseException, IOException {
      // do nothing
    }
    @Override
    public void endJSON() throws ParseException, IOException {
      Context context = contextStack.peek();
      context.object = null;
      context.state = ContextState.END_STREAM;
    }

    @Override
    public boolean startArray() throws ParseException, IOException {
      return true;
    }
    @Override
    public boolean endArray() throws ParseException, IOException {
      return true;
    }


    @Override
    public boolean startObjectEntry(String key) throws ParseException, IOException {
      Context context = contextStack.peek();
      if (context.state == ContextState.DISCARD) {
        contextStack.push(DISCARD_CONTEXT);
        return true;
      }

      //System.out.println("start object entry " + key + " " + context.builder.ruleMap);
      
      Object rule = context.builder.ruleMap.get(key);
      if (rule == null) {
        contextStack.push(DISCARD_CONTEXT);
        return true;
      }

      if (rule instanceof JSONSchemaBuilder) {
        JSONSchemaBuilder<?> builder = (JSONSchemaBuilder<?>)rule;
        Context newContext = new Context(builder, ContextState.START_STREAM);
        newContext.object = context.object;  // send object of previous context 
        contextStack.push(newContext);
        return false;   // stop parsing to spin a stream
      } 

      // value rule
      valueSetter = (MethodHandle)rule;
      return true;
    }
    @Override
    public boolean endObjectEntry() throws ParseException, IOException {
      Context context = contextStack.peek();
      if (context.state == ContextState.DISCARD) {
        contextStack.pop();
        return true;
      }

      if (valueSetter != null) {  // is it a value entry
        valueSetter = null;
        return true;
      }

      // it's an object entry
      context.object = null;
      context.state = ContextState.END_STREAM;
      contextStack.pop();
      return false;  // stop parsing
    }

    @Override
    public boolean startObject() throws ParseException, IOException {
      Context context = contextStack.peek();
      if (context.state == ContextState.DISCARD) {
        return true;
      }
      
      try {
        context.object = context.builder.constructor.invokeExact();
      } catch(Error | RuntimeException e) {
        throw e;
      } catch(Throwable t) {
        throw new AssertionError(t);
      }
      return true;
    }
    
    @Override
    public boolean endObject() throws ParseException, IOException {
      //System.out.println("end object");
      Context context = contextStack.peek();
      
      // here, we don't test context.state because the context can
      // still hold an un-published object
      if (context == DISCARD_CONTEXT) {
        return true;
      }
      
      context.state = ContextState.PUBLISH;
      return false;  // stop parsing
    }

    @Override
    public boolean primitive(Object value) throws ParseException, IOException {
      Context context = contextStack.peek();
      if (context.state == ContextState.DISCARD) {
        return true;
      }
      if (valueSetter == null) {
        throw new IllegalStateException("schema mismatch, value is said to be an object not a primitive");
      }
      
      try {
        valueSetter.invokeExact(context.object, value);
      } catch(Error|RuntimeException e) {
        throw e;
      } catch(Throwable t) {
        throw new AssertionError(t);
      }
      return true;
    }
  }

  private void freeze() {
    //System.out.println("ruleMap.values " + ruleMap.values());
    
    // recursively freeze all builders
    for(Object rule: ruleMap.values()) {
      if (!(rule instanceof JSONSchemaBuilder)) {
        continue;
      }
      ((JSONSchemaBuilder<?>)rule).freeze(); 
    }
    
    if (!allowImplicit)
      return;

    for(Field field: type.getDeclaredFields()) {
      String name = field.getName();
      if (ruleMap.containsKey(name)) {
        continue;
      }
      ruleMap.put(name, asSetter(lookup, field));
    }
    
    //System.out.println("freeze "+ruleMap+" for builder "+this);
  }
  
  private static MethodHandle getConstructor(Lookup lookup, Class<?> type) {
    MethodHandle mh;
    try {
      mh = lookup.findConstructor(type, methodType(void.class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
    return mh.asType(MethodType.methodType(Object.class));
  } 
  
  private static MethodHandle asSetter(Lookup lookup, Field field) {
    MethodHandle mh;
    try {
      mh = lookup.unreflectSetter(field);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
    return mh.asType(methodType(void.class, Object.class, Object.class));
  }
  
  private static MethodHandle asSetter(Class<?> type, BiConsumer<?, ?> valueConsumer) {
    return filterArguments(BICONSUMER_ACCEPT.bindTo(valueConsumer), 1, CLASS_CAST.bindTo(type));
  }
  
  private static MethodHandle asSetter(MethodHandle biConsumerMH, Object valueConsumer, Class<?> wrapperType) {
    return biConsumerMH.bindTo(valueConsumer).
        asType(methodType(void.class, Object.class, wrapperType)).
        asType(methodType(void.class, Object.class, Object.class));
  }
  
  private static final MethodHandle BICONSUMER_ACCEPT,
      BIINTVALUECONSUMER_ACCEPT,
      BILONGVALUECONSUMER_ACCEPT,
      BIDOUBLEVALUECONSUMER_ACCEPT,
      CLASS_CAST;
  static {
    try {
      BICONSUMER_ACCEPT = publicLookup().findVirtual(BiConsumer.class, "accept",
          methodType(void.class, Object.class, Object.class));
      BIINTVALUECONSUMER_ACCEPT = publicLookup().findVirtual(BiIntValueConsumer.class, "accept",
          methodType(void.class, Object.class, int.class));
      BILONGVALUECONSUMER_ACCEPT = publicLookup().findVirtual(BiLongValueConsumer.class, "accept",
          methodType(void.class, Object.class, long.class));
      BIDOUBLEVALUECONSUMER_ACCEPT = publicLookup().findVirtual(BiDoubleValueConsumer.class, "accept",
          methodType(void.class, Object.class, double.class));
      CLASS_CAST = publicLookup().findVirtual(Class.class, "cast",
          methodType(Object.class, Object.class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new AssertionError(e);
    }
  }
  
  
  /**
   * Create a description (a schema) of a JSON that will be used later
   * to parse a JSON file/reader as a stream of object.
   * 
   * @param type the type of the top level objects in the JSON. 
   * @param builderConsumer consumer that will be called with a builder to provide
   *        a description of the values and entries of the top level object. 
   * @return a schema of a JSON stream.
   * 
   * @see JSONSchema#stream(Reader)
   * @see #schema(Lookup, Class, Consumer)
   */
  public static <T> JSONSchema<T> schema(Class<T> type, Consumer<JSONSchemaBuilder<T>> builderConsumer) {
    return schema(MethodHandles.publicLookup(), type, builderConsumer);
  }

  /**
   * Create a description (a schema) of a JSON that will be used later
   * to parse a JSON file/reader as a stream of object.
   * 
   * @param lookup the {@link Lookup} object used to find the field of class
   *        describing the JSON schema.
   * @param type the type of the top level objects in the JSON. 
   * @param builderConsumer consumer that will be called with a builder to provide
   *        a description of the values and entries of the top level object. 
   * @return a schema of a JSON stream.
   * 
   * @see #schema(Lookup, Class, Consumer)
   */
  public static <T> JSONSchema<T> schema(Lookup lookup, Class<T> type, Consumer<JSONSchemaBuilder<T>> builderConsumer) {
    JSONSchemaBuilder<T> builder = new JSONSchemaBuilder<>(lookup, type, null);
    builderConsumer.accept(builder);
    builder.freeze();
    return reader -> {
      Objects.requireNonNull(reader);
      Handler handler = new Handler(new JSONParser(), reader);
      Context context = handler.createFirstContext(builder);
      return builder.createAStream(handler, context);
    };
  }

  /**
   * Declares that the object described by the current builder has
   * an entry named {@code key} of type {@code type}.
   * 
   * @param key name of the entry.
   * @param type type of the objects of this entry.
   * @param builderConsumer a consumer that will be called with a builder to provide
   *        a description of the values and entries of the objects of this entry. 
   * @param streamConsumer a consumer that will be called with a stream of the JSON
   *        object corresponding to the entry during the parsing.
   * @return the current schema builder.
   */
  public <U> JSONSchemaBuilder<T> entry(String key, Class<U> type, Consumer<JSONSchemaBuilder<U>> builderConsumer, BiConsumer<? super T, Stream<U>> streamConsumer) {
    @SuppressWarnings("unchecked")
    JSONSchemaBuilder<U> builder = new JSONSchemaBuilder<>(lookup, type, (BiConsumer<Object, Stream<U>>)streamConsumer);
    ruleMap.put(key, builder);
    builderConsumer.accept(builder);
    return this;
  }

  /**
   * Declares that the object described by the current builder has
   * an entry named {@code key} of type {@code type}.
   * 
   * @param key name of the entry.
   * @param type type of the objects of this entry.
   * @param streamConsumer a consumer that will be called with a stream of the JSON
   *        object corresponding to the entry during the parsing.
   * @return the current schema builder.
   */
  public <U> JSONSchemaBuilder<T> entry(String key, Class<U> type, BiConsumer<? super T, Stream<U>> streamConsumer) {
    @SuppressWarnings("unchecked")
    JSONSchemaBuilder<U> builder = new JSONSchemaBuilder<>(lookup, type, (BiConsumer<Object, Stream<U>>)streamConsumer);
    ruleMap.put(key, builder);
    return this;
  }

  /**
   * Declares that the object described by the current builder has
   * a value named {@code key} of type {@code type}.
   * 
   * @param key name of the value.
   * @param type type of the objects of this entry.
   * @param streamConsumer a consumer that will be called with
   *        the object described by the current builder and the parsed value.
   * @return the current schema builder.
   */
  public <U> JSONSchemaBuilder<T> value(String key, Class<U> type, BiConsumer<? super T, ? super U> valueConsumer) {
    if (type.isPrimitive()) {
      throw new IllegalArgumentException("primitive type are not valid here");
    }
    ruleMap.put(key, asSetter(BICONSUMER_ACCEPT, valueConsumer, type));
    return this;
  }

  /**
   * Declares that the object described by the current builder has
   * a primitive value named {@code key} of type int.
   * 
   * @param key name of the value.
   * @param streamConsumer a consumer that will be called with
   *        the object described by the current builder and the parsed value.
   * @return the current schema builder.
   */
  public JSONSchemaBuilder<T> value(String key, BiIntValueConsumer<? super T> valueConsumer) {
    ruleMap.put(key, asSetter(BIINTVALUECONSUMER_ACCEPT, valueConsumer, Integer.class));
    return this;
  }

  /**
   * Declares that the object described by the current builder has
   * a primitive value named {@code key} of type long.
   * 
   * @param key name of the value.
   * @param streamConsumer a consumer that will be called with
   *        the object described by the current builder and the parsed value.
   * @return the current schema builder.
   */
  public JSONSchemaBuilder<T> value(String key, BiLongValueConsumer<? super T> valueConsumer) {
    ruleMap.put(key, asSetter(BILONGVALUECONSUMER_ACCEPT, valueConsumer, Long.class));
    return this;
  }

  /**
   * Declares that the object described by the current builder has
   * a primitive value named {@code key} of type long.
   * 
   * @param key name of the value.
   * @param streamConsumer a consumer that will be called with
   *        the object described by the current builder and the parsed value.
   * @return the current schema builder.
   */
  public JSONSchemaBuilder<T> value(String key, BiDoubleValueConsumer<? super T> valueConsumer) {
    ruleMap.put(key, asSetter(BIDOUBLEVALUECONSUMER_ACCEPT, valueConsumer, Double.class));
    return this;
  }

  /**
   * By default, the declared fields (not the inhereted ones) are automatically declared as value.
   * This method allow to override this default behavior.
   * @return
   */
  public JSONSchemaBuilder<T> disallowImplicit() {
    allowImplicit = false;
    return this;
  }
}
