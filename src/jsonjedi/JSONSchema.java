package jsonjedi;

import java.io.IOException;
import java.io.Reader;
import java.util.stream.Stream;

/**
 * Description of a JSON format.
 * 
 * @param <T> type of the object in the stream.
 */
@FunctionalInterface
public interface JSONSchema<T> {
  /**
   * Returns a stream that if read will lazily provide the JSON objects
   * encoded in the reader using the current JSON schema.
   * Once created, objects can be safely used anywhere in the program.
   * 
   * If an IOException occurs while pumping objects from the stream,
   * an {@link java.io.IOError} will be raised.
   * 
   * @param reader input stream reader
   * @return a stream of objects corresponding to the current JSON schema.
   * @throws IOException throws if an IO error occurs when creating
   *         the stream.
   */
  public Stream<T> stream(Reader reader) throws IOException;
}
