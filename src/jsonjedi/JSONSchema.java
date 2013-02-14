package jsonjedi;

import java.io.IOException;
import java.io.Reader;
import java.util.stream.Stream;

@FunctionalInterface
public interface JSONSchema<T> {
  public Stream<T> stream(Reader reader) throws IOException;
}
