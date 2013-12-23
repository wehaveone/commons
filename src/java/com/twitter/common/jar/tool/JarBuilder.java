package com.twitter.common.jar.tool;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes.Name;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.base.Splitter;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.common.io.ByteStreams;
import com.google.common.io.Closer;
import com.google.common.io.Files;
import com.google.common.io.InputSupplier;

import com.twitter.common.base.ExceptionalClosure;
import com.twitter.common.base.Function;
import com.twitter.common.io.FileUtils;

/**
 * A utility than can create or update jar archives with special handling of duplicate entries.
 */
public class JarBuilder implements Closeable {

  /**
   * Indicates a problem encountered when building up a jar's contents for writing out.
   */
  public static class JarBuilderException extends IOException {
    public JarBuilderException(String message) {
      super(message);
    }

    public JarBuilderException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * Indicates a problem writing out a jar.
   */
  public static class JarCreationException extends JarBuilderException {
    public JarCreationException(String message) {
      super(message);
    }
  }

  /**
   * Indicates a problem indexing a pre-existing jar that will be added or updated to the target
   * jar.
   */
  public static class IndexingException extends JarBuilderException {
    public IndexingException(File jarPath, Throwable t) {
      super("Problem indexing jar at " + jarPath, t);
    }
  }

  /**
   * Indicates a duplicate jar entry is being rejected.
   */
  public static class DuplicateEntryException extends RuntimeException {
    private final ReadableEntry entry;

    DuplicateEntryException(ReadableEntry entry) {
      super("Detected a duplicate entry for " + entry.getJarPath());
      this.entry = entry;
    }

    /**
     * Returns the duplicate path.
     */
    public String getPath() {
      return entry.getJarPath();
    }

    /**
     * Returns the contents of the duplicate entry.
     */
    public InputSupplier<? extends InputStream> getSource() {
      return entry.contents;
    }
  }

  /**
   * Identifies an action to take when duplicate jar entries are encountered.
   */
  public enum DuplicateAction {

    /**
     * This action skips the duplicate entry keeping the original entry.
     */
    SKIP,

    /**
     * This action replaces the original entry with the duplicate entry.
     */
    REPLACE,

    /**
     * This action appends the content of the duplicate entry to the original entry.
     */
    CONCAT,

    /**
     * This action throws a {@link DuplicateEntryException}.
     */
    THROW
  }

  /**
   * Encapsulates a policy for treatment of duplicate jar entries.
   */
  public static class DuplicatePolicy implements Predicate<CharSequence> {

    /**
     * Creates a policy that applies to entries based on a path match.
     *
     * @param regex A regular expression to match entry paths against.
     * @param action The action to apply to duplicate entries with path matching {@code regex}.
     * @return The path matching policy.
     */
    public static DuplicatePolicy pathMatches(String regex, DuplicateAction action) {
      return new DuplicatePolicy(Predicates.containsPattern(regex), action);
    }

    private final Predicate<CharSequence> selector;
    private final DuplicateAction action;

    /**
     * Creates a policy that will be applied to duplicate entries matching the given
     * {@code selector}.
     *
     * @param selector A predicate that selects entries this policy has jurisdiction over.
     * @param action The action to apply to entries selected by this policy.
     */
    public DuplicatePolicy(Predicate<CharSequence> selector, DuplicateAction action) {
      this.selector = Preconditions.checkNotNull(selector);
      this.action = Preconditions.checkNotNull(action);
    }

    /**
     * Returns the action that should be applied when a duplicate entry falls under this policy's
     * jurisdiction.
     */
    public DuplicateAction getAction() {
      return action;
    }

    @Override
    public boolean apply(CharSequence jarPath) {
      return selector.apply(jarPath);
    }
  }

  /**
   * Handles duplicate jar entries by selecting an appropriate action based on the entry path.
   */
  public static class DuplicateHandler {

    /**
     * Creates a handler that always applies the given {@code action}.
     *
     * @param action The action to perform on all duplicate entries encountered.
     */
    public static DuplicateHandler always(DuplicateAction action) {
      Preconditions.checkNotNull(action);
      return new DuplicateHandler(action,
          ImmutableList.of(new DuplicatePolicy(Predicates.<CharSequence>alwaysTrue(), action)));
    }

    /**
     * Creates a handler that merges well-known mergeable resources and otherwise skips duplicates.
     * <p/>
     * Merged resources include META-INF/services/ files.
     */
    public static DuplicateHandler skipDuplicatesConcatWellKnownMetadata() {
      DuplicatePolicy concatServices =
          DuplicatePolicy.pathMatches("^META-INF/services/", DuplicateAction.CONCAT);
      ImmutableList<DuplicatePolicy> policies = ImmutableList.of(concatServices);
      return new DuplicateHandler(DuplicateAction.SKIP, policies);
    }

    private final DuplicateAction defaultAction;
    private final Iterable<DuplicatePolicy> policies;

    /**
     * A convenience constructor equivalent to calling:
     * {@code DuplicateHandler(defaultAction, Arrays.asList(policies))}
     */
    public DuplicateHandler(DuplicateAction defaultAction, DuplicatePolicy... policies) {
      this(defaultAction, ImmutableList.copyOf(policies));
    }

    /**
     * Creates a handler that applies the 1st matching policy when a duplicate entry is encountered,
     * falling back to the given {@code defaultAction} if no policy applies.
     *
     * @param defaultAction The default action to apply when no policy matches.
     * @param policies The policies to apply in preference order.
     */
    public DuplicateHandler(DuplicateAction defaultAction, Iterable<DuplicatePolicy> policies) {
      this.defaultAction = Preconditions.checkNotNull(defaultAction);
      this.policies = ImmutableList.copyOf(policies);
    }

    @VisibleForTesting
    DuplicateAction actionFor(String jarPath) {
      for (DuplicatePolicy policy : policies) {
        if (policy.apply(jarPath)) {
          return policy.getAction();
        }
      }
      return defaultAction;
    }
  }

  /**
   * Identifies a source for jar entries.
   */
  public interface Source {
    /**
     * Returns a name for this source.
     */
    String name();

    /**
     * Identifies a member of this source.
     */
    String identify(String name);
  }

  private abstract static class FileSource implements Source {
    protected final File source;

    protected FileSource(File source) {
      this.source = source;
    }

    public String name() {
      return source.getPath();
    }
  }

  private static Source jarSource(File jar) {
    return new FileSource(jar) {
      @Override public String identify(String name) {
        return String.format("%s!%s", source.getPath(), name);
      }
      @Override public String toString() {
        return String.format("FileSource{jar=%s}", source.getPath());
      }
    };
  }

  private static Source fileSource(final File file) {
    return new FileSource(new File("/")) {
      @Override public String identify(String name) {
        if (!file.getPath().equals(name)) {
          throw new IllegalArgumentException(
              "Cannot identify any entry name save for " + file.getPath());
        }
        return file.getPath();
      }
      @Override public String toString() {
        return String.format("FileSource{file=%s}", file.getPath());
      }
    };
  }

  private static Source directorySource(File directory) {
    return new FileSource(directory) {
      @Override public String identify(String name) {
        return new File(source, name).getPath();
      }
      @Override public String toString() {
        return String.format("FileSource{directory=%s}", source.getPath());
      }
    };
  }

  private static Source memorySource() {
    return new Source() {
      @Override public String name() {
        return "<memory>";
      }
      @Override public String identify(String name) {
        return "<memory>!" + name;
      }
      @Override public String toString() {
        return String.format("MemorySource{@%s}", Integer.toHexString(hashCode()));
      }
    };
  }

  private static final class NamedInputSupplier<T> implements InputSupplier<T> {
    static <I> NamedInputSupplier<I> create(
        Source source,
        String name,
        InputSupplier<I> inputSupplier) {

      return new NamedInputSupplier<I>(source, name, inputSupplier);
    }

    private final Source source;
    private final String name;
    private final InputSupplier<T> inputSupplier;

    private NamedInputSupplier(Source source, String name, InputSupplier<T> tInputSupplier) {
      this.source = source;
      this.name = name;
      this.inputSupplier = tInputSupplier;
    }

    @Override
    public T getInput() throws IOException {
      return inputSupplier.getInput();
    }
  }

  /**
   * Represents an entry to be added to a jar.
   */
  public interface Entry {
    /**
     * Returns the source that contains the entry.
     */
    Source getSource();

    /**
     * Returns the name of the entry within its source.
     */
    String getName();

    /**
     * Returns the path this entry will be added into the jar at.
     */
    String getJarPath();
  }

  private static class ReadableEntry implements Entry {
    static final Function<ReadableEntry, NamedInputSupplier<? extends InputStream>> GET_CONTENTS =
        new Function<ReadableEntry, NamedInputSupplier<? extends InputStream>>() {
          @Override public NamedInputSupplier<? extends InputStream> apply(ReadableEntry item) {
            return item.contents;
          }
        };

    private final NamedInputSupplier<? extends InputStream> contents;
    private final String path;

    ReadableEntry(NamedInputSupplier<? extends InputStream> contents, String path) {
      this.contents = contents;
      this.path = path;
    }

    @Override
    public Source getSource() {
      return contents.source;
    }

    @Override
    public String getName() {
      return contents.name;
    }

    @Override
    public String getJarPath() {
      return path;
    }
  }

  /**
   * An interface for those interested in the progress of writing the target jar.
   */
  public interface Listener {
    /**
     * A listener that ignores all events.
     */
    Listener NOOP = new Listener() {
      @Override public void onSkip(Optional<? extends Entry> original,
                                   Iterable<? extends Entry> skipped) {
        // noop
      }
      @Override public void onReplace(Iterable<? extends Entry> originals, Entry replacement) {
        // noop
      }
      @Override public void onConcat(String name, Iterable<? extends Entry> entries) {
        // noop
      }
      @Override public void onWrite(Entry entry) {
        // noop
      }
    };

    /**
     * Called to notify the listener that entries are being skipped.
     *
     * If original is present this indicates it it being retained in preference to the skipped
     * entries.
     *
     * @param original The original entry being retained.
     * @param skipped The new entries being skipped.
     */
    void onSkip(Optional<? extends Entry> original, Iterable<? extends Entry> skipped);

    /**
     * Called to notify the listener that original entries are being replaced by a subsequently
     * added entry.
     *
     * @param originals The original entry candidates that will be replaced.
     * @param replacement The entry that overwrites the originals.
     */
    void onReplace(Iterable<? extends Entry> originals, Entry replacement);

    /**
     * Called to notify the listener an original entry is being concatenated with one or more
     * subsequently added entries.
     *
     * @param name The name of the entry in question.
     * @param entries The entries that will be concatenated with the original entry.
     */
    void onConcat(String name, Iterable<? extends Entry> entries);

    /**
     * Called to notify the listener of a newly written non-duplicate entry.
     *
     * @param entry The entry to be added to the target jar.
     */
    void onWrite(Entry entry);
  }

  private static InputSupplier<InputStream> manifestSupplier(final Manifest mf) {
    return new InputSupplier<InputStream>() {
      @Override public InputStream getInput() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        mf.write(out);
        return new ByteArrayInputStream(out.toByteArray());
      }
    };
  }

  static Manifest createDefaultManifest() {
    Manifest mf = new Manifest();
    mf.getMainAttributes().put(Name.MANIFEST_VERSION, "1.0");
    mf.getMainAttributes().put(new Name("Created-By"), JarBuilder.class.getName());
    return mf;
  }

  private static final InputSupplier<InputStream> DEFAULT_MANIFEST =
      manifestSupplier(createDefaultManifest());

  private static class JarSupplier implements InputSupplier<JarFile>, Closeable {
    private final Closer closer;
    private final InputSupplier<JarFile> supplier;

    JarSupplier(final File file) {
      closer = Closer.create();
      supplier = new InputSupplier<JarFile>() {
        @Override public JarFile getInput() throws IOException {
          return closer.register(new JarFile(file));
        }
      };
    }

    @Override
    public JarFile getInput() throws IOException {
      return supplier.getInput();
    }

    @Override
    public void close() throws IOException {
      closer.close();
    }
  }

  private static final Splitter JAR_PATH_SPLITTER = Splitter.on('/');
  private static final Joiner JAR_PATH_JOINER = Joiner.on('/');

  /*
   * Implementations should add jar entries to the given {@code Multimap} index when executed.
   */
  private interface EntryIndexer
      extends ExceptionalClosure<Multimap<String, ReadableEntry>, JarBuilderException> {

    // typedef
  };

  private final File target;
  private final Listener listener;
  private final Closer closer = Closer.create();
  private final List<EntryIndexer> additions = Lists.newLinkedList();

  @Nullable private InputSupplier<InputStream> manifest;

  /**
   * Creates a JarBuilder that will write scheduled jar additions to {@code target} upon
   * {@link #write()}.
   * <p>
   * If the {@code target} does not exist a new jar will be created at its path.
   *
   * @param target The target jar file to write.
   */
  public JarBuilder(File target) {
    this(target, Listener.NOOP);
  }

  /**
   * Creates a JarBuilder that will write scheduled jar additions to {@code target} upon
   * {@link #write()}.
   * <p>
   * If the {@code target} does not exist a new jar will be created at its path.
   *
   * @param target The target jar file to write.
   */
  public JarBuilder(File target, Listener listener) {
    this.target = Preconditions.checkNotNull(target);
    this.listener = Preconditions.checkNotNull(listener);
  }

  @Override
  public void close() throws IOException {
    closer.close();
  }

  /**
   * Schedules addition of the given {@code contents} to the entry at {@code jarPath}. In addition,
   * individual parent directory entries will be created when this builder is
   * {@link #write() written} in he spirit of {@code mkdir -p}.
   *
   * @param contents The contents of the entry to add.
   * @param jarPath The path of the entry to add.
   * @return This builder for chaining.
   */
  public JarBuilder add(
      final InputSupplier<? extends InputStream> contents,
      final String jarPath) {

    Preconditions.checkNotNull(contents);
    Preconditions.checkNotNull(jarPath);

    additions.add(new EntryIndexer() {
      @Override public void execute(Multimap<String, ReadableEntry> entries) {
        add(entries, NamedInputSupplier.create(memorySource(), jarPath, contents), jarPath);
      }
    });
    return this;
  }

  /**
   * Schedules addition of the given {@code file}'s contents to the entry at {@code jarPath}. In
   * addition, individual parent directory entries will be created when this builder is
   * {@link #write() written} in the spirit of {@code mkdir -p}.  If the file points to a directory,
   * its subtree is scheduled for addition rooted at {@code jarPath} in the resulting jar.
   *
   * @param file And existing file or directory to add to the jar.
   * @param jarPath The path of the entry to add.
   * @return This builder for chaining.
   */
  public JarBuilder add(final File file, final String jarPath) {
    Preconditions.checkNotNull(file);
    Preconditions.checkNotNull(jarPath);

    additions.add(new EntryIndexer() {
      @Override public void execute(Multimap<String, ReadableEntry> entries)
          throws JarBuilderException {

        if (file.isDirectory()) {
          Source directorySource = directorySource(file);
          Iterable<String> jarBasePath = JAR_PATH_SPLITTER.split(jarPath);
          Collection<File> files =
              org.apache.commons.io.FileUtils.listFiles(
                  file,
                  null /* any extension */,
                  true /* recursive */);
          for (File child : files) {
            Iterable<String> path = Iterables.concat(jarBasePath, relpathComponents(child, file));
            String entryPath = JAR_PATH_JOINER.join(relpathComponents(child, file));
            if (!JarFile.MANIFEST_NAME.equals(entryPath)) {
              NamedInputSupplier<FileInputStream> contents =
                  NamedInputSupplier.create(
                      directorySource,
                      entryPath,
                      Files.newInputStreamSupplier(child));
              add(entries, contents, JAR_PATH_JOINER.join(path));
            }
          }
        } else {
          if (JarFile.MANIFEST_NAME.equals(jarPath)) {
            throw new JarBuilderException(
                "A custom manifest entry should be added via the useCustomManifest methods");
          }
          NamedInputSupplier<FileInputStream> contents =
              NamedInputSupplier.create(
                  fileSource(file),
                  file.getName(),
                  Files.newInputStreamSupplier(file));
          add(entries, contents, jarPath);
        }
      }
    });
    return this;
  }

  /**
   * Schedules addition of the given jar's contents to the file at {@code jarPath}. Even if the jar
   * does not contain individual parent directory entries, they will be added for each entry added.
   *
   * @param file The path of the jar to add.
   * @return This builder for chaining.
   */
  public JarBuilder addJar(final File file) {
    Preconditions.checkNotNull(file);

    additions.add(new EntryIndexer() {
      @Override public void execute(final Multimap<String, ReadableEntry> entries)
          throws IndexingException {

        final InputSupplier<JarFile> jarSupplier = closer.register(new JarSupplier(file));
        final Source jarSource = jarSource(file);
        try {
          enumerateJarEntries(file, new ExceptionalClosure<JarEntry, IOException>() {
            @Override public void execute(JarEntry entry) throws IOException {
              if (!entry.isDirectory() && !JarFile.MANIFEST_NAME.equals(entry.getName())) {
                NamedInputSupplier<InputStream> contents =
                    NamedInputSupplier.create(
                        jarSource,
                        entry.getName(),
                        entrySupplier(jarSupplier, entry));
                add(entries, contents, entry.getName());
              }
            }
          });
        } catch (IOException e) {
          throw new IndexingException(file, e);
        }
      }
    });
    return this;
  }

  private static void add(
      Multimap<String, ReadableEntry> entries,
      final NamedInputSupplier<? extends InputStream> contents,
      final String jarPath) {

    entries.put(jarPath, new ReadableEntry(contents, jarPath));
  }

  /**
   * Registers the given Manifest to be used in the jar written out by {@link #write()}.
   *
   * @param customManifest The manifest to use for the built jar.
   * @return This builder for chaining.
   */
  public JarBuilder useCustomManifest(final Manifest customManifest) {
    Preconditions.checkNotNull(customManifest);

    manifest = manifestSupplier(customManifest);
    return this;
  }

  /**
   * Registers the given Manifest to be used in the jar written out by {@link #write()}.
   *
   * @param customManifest The manifest to use for the built jar.
   * @return This builder for chaining.
   */
  public JarBuilder useCustomManifest(File customManifest) {
    Preconditions.checkNotNull(customManifest);

    NamedInputSupplier<FileInputStream> contents =
        NamedInputSupplier.create(
            fileSource(customManifest),
            customManifest.getPath(),
            Files.newInputStreamSupplier(customManifest));
    return useCustomManifest(contents);
  }

  /**
   * Registers the given Manifest to be used in the jar written out by {@link #write()}.
   *
   * @param customManifest The manifest to use for the built jar.
   * @return This builder for chaining.
   */
  public JarBuilder useCustomManifest(CharSequence customManifest) {
    Preconditions.checkNotNull(customManifest);

    return useCustomManifest(
        NamedInputSupplier.create(
            memorySource(),
            JarFile.MANIFEST_NAME,
            ByteStreams.newInputStreamSupplier(
                customManifest.toString().getBytes(Charsets.UTF_8))));
  }

  /**
   * Registers the given Manifest to be used in the jar written out by {@link #write()}.
   *
   * @param customManifest The manifest to use for the built jar.
   * @return This builder for chaining.
   */
  public JarBuilder useCustomManifest(
      final NamedInputSupplier<? extends InputStream> customManifest) {

    Preconditions.checkNotNull(customManifest);
    return useCustomManifest(new InputSupplier<Manifest>() {
      @Override public Manifest getInput() throws IOException {
        Manifest mf = new Manifest();
        try {
          mf.read(customManifest.getInput());
          return mf;
        } catch (IOException e) {
          throw new JarCreationException(
              "Invalid manifest from " + customManifest.source.identify(customManifest.name));
        }
      }
    });
  }

  private JarBuilder useCustomManifest(final InputSupplier<Manifest> manifestSource) {
    manifest = new InputSupplier<InputStream>() {
      @Override public InputStream getInput() throws IOException {
        return manifestSupplier(manifestSource.getInput()).getInput();
      }
    };
    return this;
  }

  /**
   * Creates a jar at the configured target path applying the scheduled additions and skipping any
   * duplicate entries found.
   *
   * @return The jar file that was written.
   * @throws IOException if there was a problem writing the jar file.
   */
  public File write() throws IOException {
    return write(DuplicateHandler.always(DuplicateAction.SKIP));
  }

  /**
   * Creates a jar at the configured target path applying the scheduled additions per the given
   * {@code duplicateHandler}.
   *
   * @param duplicateHandler A handler for dealing with duplicate entries.
   * @param skipPatterns An optional list of patterns that match entry paths that should be
   *     excluded.
   * @return The jar file that was written.
   * @throws IOException if there was a problem writing the jar file.
   * @throws DuplicateEntryException if the the policy in effect for an entry is
   *     {@link DuplicateAction#THROW} and that entry is a duplicate.
   */
  public File write(DuplicateHandler duplicateHandler, Pattern... skipPatterns) throws IOException {
    return write(duplicateHandler, ImmutableList.copyOf(skipPatterns));
  }

  private static final Function<Pattern, Predicate<CharSequence>> AS_PATH_SELECTOR =
      new Function<Pattern, Predicate<CharSequence>>() {
        @Override public Predicate<CharSequence> apply(Pattern item) {
          return Predicates.contains(item);
        }
      };

  /**
   * Creates a jar at the configured target path applying the scheduled additions per the given
   * {@code duplicateHandler}.
   *
   * @param duplicateHandler A handler for dealing with duplicate entries.
   * @param skipPatterns An optional sequence of patterns that match entry paths that should be
   *     excluded.
   * @return The jar file that was written.
   * @throws IOException if there was a problem writing the jar file.
   * @throws DuplicateEntryException if the the policy in effect for an entry is
   *     {@link DuplicateAction#THROW} and that entry is a duplicate.
   */
  public File write(DuplicateHandler duplicateHandler, Iterable<Pattern> skipPatterns)
      throws DuplicateEntryException, IOException {

    Preconditions.checkNotNull(duplicateHandler);
    Predicate<CharSequence> skipPath =
        Predicates.or(Iterables.transform(ImmutableList.copyOf(skipPatterns), AS_PATH_SELECTOR));

    final Iterable<ReadableEntry> entries = getEntries(skipPath, duplicateHandler);

    FileUtils.SYSTEM_TMP.doWithFile(new ExceptionalClosure<File, IOException>() {
      @Override public void execute(File tmp) throws IOException {
        try {
          JarWriter writer = jarWriter(tmp);
          writer.write(JarFile.MANIFEST_NAME, manifest == null ? DEFAULT_MANIFEST : manifest);
          for (ReadableEntry entry : entries) {
            writer.write(entry.getJarPath(), entry.contents);
          }
        } catch (IOException e) {
          throw closer.rethrow(e);
        } finally {
          closer.close();
        }
        if (!tmp.renameTo(target)) {
          throw new JarCreationException(
              String.format("Problem moving created jar from %s to %s", tmp, target));
        }
      }
    });
    return target;
  }

  private Iterable<ReadableEntry> getEntries(
      final Predicate<CharSequence> skipPath,
      final DuplicateHandler duplicateHandler)
      throws JarBuilderException {

    Function<Map.Entry<String, Collection<ReadableEntry>>, Iterable<ReadableEntry>> mergeEntries =
        new Function<Map.Entry<String, Collection<ReadableEntry>>, Iterable<ReadableEntry>>() {
          @Override
          public Iterable<ReadableEntry> apply(Map.Entry<String, Collection<ReadableEntry>> item) {
            String jarPath = item.getKey();
            Collection<ReadableEntry> entries = item.getValue();
            return processEntries(skipPath, duplicateHandler, jarPath, entries).asSet();
          }
        };
    return FluentIterable.from(getAdditions().asMap().entrySet()).transformAndConcat(mergeEntries);
  }

  private Optional<ReadableEntry> processEntries(
      Predicate<CharSequence> skipPath,
      DuplicateHandler duplicateHandler,
      String jarPath,
      Collection<ReadableEntry> itemEntries) {

    if (skipPath.apply(jarPath)) {
      listener.onSkip(Optional.<Entry>absent(), itemEntries);
      return Optional.absent();
    }

    if (itemEntries.size() < 2) {
      ReadableEntry entry = Iterables.getOnlyElement(itemEntries);
      listener.onWrite(entry);
      return Optional.of(entry);
    }

    DuplicateAction action = duplicateHandler.actionFor(jarPath);
    switch (action) {
      case SKIP:
        ReadableEntry original = Iterables.get(itemEntries, 0);
        listener.onSkip(Optional.of(original), Iterables.skip(itemEntries, 1));
        return Optional.of(original);

      case REPLACE:
        ReadableEntry replacement = Iterables.getLast(itemEntries);
        listener.onReplace(Iterables.limit(itemEntries, itemEntries.size() - 1), replacement);
        return Optional.of(replacement);

      case CONCAT:
        InputSupplier<InputStream> concat =
            ByteStreams.join(Iterables.transform(itemEntries, ReadableEntry.GET_CONTENTS));

        ReadableEntry concatenatedEntry =
            new ReadableEntry(
                NamedInputSupplier.create(memorySource(), jarPath, concat),
                jarPath);

        listener.onConcat(jarPath, itemEntries);
        return Optional.of(concatenatedEntry);

      case THROW:
        throw new DuplicateEntryException(Iterables.get(itemEntries, 1));

      default:
        throw new IllegalArgumentException("Unrecognized DuplicateAction " + action);
    }
  }

  private Multimap<String, ReadableEntry> getAdditions() throws JarBuilderException {
    final Multimap<String, ReadableEntry> entries = LinkedListMultimap.create();
    if (target.exists() && target.length() > 0) {
      final InputSupplier<JarFile> jarSupplier = closer.register(new JarSupplier(target));
      try {
        enumerateJarEntries(target, new ExceptionalClosure<JarEntry, IOException>() {
          @Override public void execute(JarEntry jarEntry) throws IOException {
            String entryPath = jarEntry.getName();
            InputSupplier<InputStream> contents = entrySupplier(jarSupplier, jarEntry);
            if (JarFile.MANIFEST_NAME.equals(entryPath)) {
              if (manifest == null) {
                manifest = contents;
              }
            } else if (!jarEntry.isDirectory()) {
              entries.put(
                  entryPath,
                  new ReadableEntry(
                      NamedInputSupplier.create(jarSource(target), entryPath, contents),
                      entryPath));
            }
          }
        });
      } catch (IOException e) {
        throw new IndexingException(target, e);
      }
    }
    for (ExceptionalClosure<Multimap<String, ReadableEntry>,
                            JarBuilderException> addition : additions) {
      addition.execute(entries);
    }
    return entries;
  }

  private void enumerateJarEntries(File jarFile, ExceptionalClosure<JarEntry, IOException> work)
      throws IOException {

    Closer jarFileCloser = Closer.create();
    JarFile jar = jarFileCloser.register(new JarFile(jarFile));
    try {
      for (Enumeration<JarEntry> entries = jar.entries(); entries.hasMoreElements();) {
        work.execute(entries.nextElement());
      }
    } catch (IOException e) {
      throw jarFileCloser.rethrow(e);
    } finally {
      jarFileCloser.close();
    }
  }

  private static final class JarWriter {
    private static final Joiner JAR_PATH_JOINER = Joiner.on('/');

    private final Set<List<String>> directories = Sets.newHashSet();
    private final JarOutputStream out;

    private JarWriter(JarOutputStream out) {
      this.out = out;
    }

    public void write(String path, InputSupplier<? extends InputStream> contents)
        throws IOException {

      ensureParentDir(path);
      out.putNextEntry(new JarEntry(path));
      ByteStreams.copy(contents, out);
    }

    private void ensureParentDir(String path) throws IOException {
      File file = new File(path);
      File parent = file.getParentFile();
      if (parent != null) {
        List<String> components = components(parent);
        List<String> ancestry = Lists.newArrayListWithCapacity(components.size());
        for (String component : components) {
          ancestry.add(component);
          if (!directories.contains(ancestry)) {
            directories.add(ImmutableList.copyOf(ancestry));
            out.putNextEntry(new JarEntry(JAR_PATH_JOINER.join(ancestry) + "/"));
          }
        }
      }
    }
  }

  private JarWriter jarWriter(File path) throws IOException {
    FileOutputStream out = closer.register(new FileOutputStream(path));
    final JarOutputStream jar = closer.register(new JarOutputStream(out));
    closer.register(new Closeable() {
      @Override public void close() throws IOException {
        jar.closeEntry();
      }
    });
    return new JarWriter(jar);
  }

  private static InputSupplier<InputStream> entrySupplier(
      final InputSupplier<JarFile> jar,
      final JarEntry entry) {

    return new InputSupplier<InputStream>() {
      @Override public InputStream getInput() throws IOException {
        return jar.getInput().getInputStream(entry);
      }
    };
  }

  @VisibleForTesting
  static Iterable<String> relpathComponents(File fullPath, File relativeTo) {
    List<String> base = components(relativeTo);
    List<String> path = components(fullPath);
    for (Iterator<String> baseIter = base.iterator(), pathIter = path.iterator();
         baseIter.hasNext() && pathIter.hasNext();) {
      if (!baseIter.next().equals(pathIter.next())) {
        break;
      } else {
        baseIter.remove();
        pathIter.remove();
      }
    }

    if (!base.isEmpty()) {
      path.addAll(0, Collections.nCopies(base.size(), ".."));
    }
    return path;
  }

  private static List<String> components(File file) {
    LinkedList<String> components = Lists.newLinkedList();
    File path = file;
    do {
      components.addFirst(path.getName());
    } while((path = path.getParentFile()) != null);
    return components;
  }
}
