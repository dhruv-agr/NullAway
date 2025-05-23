/*
 * Copyright (C) 2018. Uber Technologies
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.uber.nullaway.jarinfer;

import static com.google.errorprone.BugPattern.SeverityLevel.WARNING;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ObjectArrays;
import com.google.common.collect.Sets;
import com.google.errorprone.BugPattern;
import com.google.errorprone.CompilationTestHelper;
import com.google.errorprone.bugpatterns.BugChecker;
import com.sun.tools.javac.main.Main;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableEntryException;
import java.security.cert.CertificateException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;
import jdk.security.jarsigner.JarSigner;
import org.apache.commons.io.FilenameUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link com.uber.nullaway.jarinfer}. */
@RunWith(JUnit4.class)
public class JarInferTest {

  @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();
  @Rule public TemporaryFolder outputFolder = new TemporaryFolder();

  private CompilationTestHelper compilationTestHelper;

  /**
   * A dummy checker to allow us to use {@link CompilationTestHelper} to compile Java code for
   * testing, as it requires a {@link BugChecker} to run.
   */
  @BugPattern(summary = "Dummy checker to use CompilationTestHelper", severity = WARNING)
  public static class DummyChecker extends BugChecker {
    public DummyChecker() {}
  }

  @Before
  public void setup() {
    compilationTestHelper =
        CompilationTestHelper.newInstance(DummyChecker.class, getClass())
            .setArgs(Arrays.asList("-d", temporaryFolder.getRoot().getAbsolutePath()));
  }

  /**
   * Create, compile, and run a unit test.
   *
   * @param testName An useful name for the unit test.
   * @param pkg Qualified package name.
   * @param cls Target class to be analyzed.
   * @param expected Map of 'method signatures' to their 'expected list of NonNull parameters'.
   * @param lines Source lines for the test code.
   */
  private void testTemplate(
      String testName,
      String pkg, // in dot syntax
      String cls,
      Map<String, Set<Integer>> expected,
      String... lines)
      throws Exception {
    compilationTestHelper
        .addSourceLines(cls + ".java", ObjectArrays.concat("package " + pkg + ";\n", lines))
        .expectResult(Main.Result.OK)
        .doTest();
    DefinitelyDerefedParamsDriver driver = new DefinitelyDerefedParamsDriver();
    Map<String, Set<Integer>> result =
        driver.run(
            temporaryFolder.getRoot().getAbsolutePath(), "L" + pkg.replaceAll("\\.", "/"), true);
    Assert.assertTrue(
        testName + ": test failed! \n" + result + " does not match " + expected,
        verify(result, new HashMap<>(expected)));
  }

  /**
   * Run a unit test with a specified jar file.
   *
   * @param pkg Qualified package name.
   * @param jarPath Path to the target jar file.
   */
  private void testJARTemplate(
      String pkg, // in dot syntax
      String jarPath // in dot syntax
      ) throws Exception {
    DefinitelyDerefedParamsDriver driver = new DefinitelyDerefedParamsDriver();
    driver.run(jarPath, "L" + pkg.replaceAll("\\.", "/"));
    String outJARPath = driver.lastOutPath;
    Assert.assertTrue("jar file not found! - " + outJARPath, new File(outJARPath).exists());
  }

  private void testAnnotationInJarTemplate(
      String testName,
      String pkg,
      String inputJarPath,
      Map<String, String> expectedToActualAnnotationsMap)
      throws Exception {
    String outputFolderPath = outputFolder.newFolder(pkg).getAbsolutePath();
    String inputJarName = FilenameUtils.getBaseName(inputJarPath);
    String outputJarPath = outputFolderPath + "/" + inputJarName + "-annotated.jar";
    DefinitelyDerefedParamsDriver driver = new DefinitelyDerefedParamsDriver();
    driver.runAndAnnotate(inputJarPath, "", outputJarPath);

    Assert.assertTrue(
        testName + ": generated jar does not match the expected jar!",
        AnnotationChecker.checkMethodAnnotationsInJar(
            outputJarPath, expectedToActualAnnotationsMap));
    Assert.assertTrue(
        testName + ": generated jar does not have all the entries present in the input jar!",
        EntriesComparator.compareEntriesInJars(outputJarPath, inputJarPath));
  }

  private void testAnnotationInAarTemplate(
      String testName,
      String pkg,
      String inputAarPath,
      Map<String, String> expectedToActualAnnotationMap)
      throws Exception {
    String outputFolderPath = outputFolder.newFolder(pkg).getAbsolutePath();
    String inputAarName = FilenameUtils.getBaseName(inputAarPath);
    String outputAarPath = outputFolderPath + "/" + inputAarName + "-annotated.aar";
    DefinitelyDerefedParamsDriver driver = new DefinitelyDerefedParamsDriver();
    driver.runAndAnnotate(inputAarPath, "", outputAarPath);

    Assert.assertTrue(
        testName + ": generated aar does not match the expected aar!",
        AnnotationChecker.checkMethodAnnotationsInAar(
            outputAarPath, expectedToActualAnnotationMap));
    Assert.assertTrue(
        testName + ": generated aar does not have all the entries present in the input aar!",
        EntriesComparator.compareEntriesInAars(outputAarPath, inputAarPath));
  }

  /**
   * Check set equality of results with expected results.
   *
   * @param result Map of 'method signatures' to their 'inferred list of NonNull parameters'.
   * @param expected Map of 'method signatures' to their 'expected list of NonNull parameters'.
   */
  private boolean verify(Map<String, Set<Integer>> result, Map<String, Set<Integer>> expected) {
    for (Map.Entry<String, Set<Integer>> entry : result.entrySet()) {
      String mtd_sign = entry.getKey();
      Set<Integer> ddParams = entry.getValue();
      if (ddParams.isEmpty()) {
        continue;
      }
      Set<Integer> xddParams = expected.get(mtd_sign);
      if (xddParams == null) {
        return false;
      }
      for (Integer var : ddParams) {
        if (!xddParams.remove(var)) {
          return false;
        }
      }
      if (!xddParams.isEmpty()) {
        return false;
      }
      expected.remove(mtd_sign);
    }
    return expected.isEmpty();
  }

  @Test
  public void emptyTest() {
    Assert.assertTrue("this test never fails!", true);
  }

  @Test
  public void toyStatic() throws Exception {
    testTemplate(
        "toyStatic",
        "toys",
        "Test",
        ImmutableMap.of(
            "toys.Test:void test(java.lang.String, toys.Foo, toys.Bar)", Sets.newHashSet(0, 2),
            "toys.Foo:boolean run(java.lang.String)", Sets.newHashSet(0)),
        "class Foo {",
        "  private String foo;",
        "  public Foo(String str) {",
        "    if (str == null) str = \"foo\";",
        "    this.foo = str;",
        "  }",
        "  public boolean run(String str) {",
        "    if (str.length() > 0) {",
        "      return str == foo;",
        "    }",
        "    return false;",
        "  }",
        "}",
        "",
        "class Bar {",
        "  private String bar;",
        "  public int b;",
        "  public Bar(String str) {",
        "    if (str == null) str = \"bar\";",
        "    this.bar = str;",
        "    this.b = bar.length();",
        "  }",
        "  public int run(String str) {",
        "    if (str != null) {",
        "      return str.length();",
        "    }",
        "    return bar.length();",
        "  }",
        "}",
        "",
        "public class Test {",
        "  public static void test(String s, Foo f, Bar b) {",
        "    if (s.length() >= 5) {",
        "      Foo f1 = new Foo(s);",
        "      f1.run(s);",
        "    } else {",
        "      f.run(s);",
        "    }",
        "    b.run(s);",
        "  }",
        "  public static void main(String arg[]) throws java.io.IOException {",
        "    String s = new String(\"test string...\");",
        "    Foo f = new Foo(\"try\");",
        "    Bar b = new Bar(null);",
        "    try {",
        "      test(s, f, b);",
        "    } catch (Error e) {",
        "      System.out.println(e.getMessage());",
        "    }",
        "  }",
        "}");
  }

  @Test
  public void toyNonStatic() throws Exception {
    testTemplate(
        "toyNonStatic",
        "toys",
        "Foo",
        ImmutableMap.of(
            "toys.Foo:void test(java.lang.String, java.lang.String)", Sets.newHashSet(0)),
        "class Foo {",
        "  private String foo;",
        "  public Foo(String str) {",
        "    if (str == null) str = \"foo\";",
        "    this.foo = str;",
        "  }",
        "  public boolean run(String str) {",
        "    if (str != null) {",
        "      return str == foo;",
        "    }",
        "    return false;",
        "  }",
        "  public void test(String s, String t) {",
        "    if (s.length() >= 5) {",
        "      this.run(s);",
        "    } else {",
        "      this.run(t);",
        "    }",
        "  }",
        "}");
  }

  @Test
  public void toyJAR() throws Exception {
    testJARTemplate(
        "com.uber.nullaway.jarinfer.toys.unannotated",
        "../test-java-lib-jarinfer/build/libs/test-java-lib-jarinfer.jar");
  }

  @Test
  public void toyNullTestAPI() throws Exception {
    testTemplate(
        "toyNullTestAPI",
        "toys",
        "Foo",
        ImmutableMap.of(
            "toys.Foo:void test(java.lang.String, java.lang.String, java.lang.String)",
            Sets.newHashSet(0, 2)),
        "import com.google.common.base.Preconditions;",
        "import java.util.Objects;",
        "import org.junit.Assert;",
        "class Foo {",
        "  private String foo;",
        "  public Foo(String str) {",
        "    if (str == null) str = \"foo\";",
        "    this.foo = str;",
        "  }",
        "  public void test(String s, String t, String u) {",
        "    Preconditions.checkNotNull(s);",
        "    Assert.assertNotNull(\"Param u is null!\", u);",
        "    if (s.length() >= 5) {",
        "      t = s;",
        "    } else {",
        "      t = u;",
        "    }",
        "    Objects.requireNonNull(t);",
        "  }",
        "}");
  }

  @Test
  public void toyConditionalFlow() throws Exception {
    testTemplate(
        "toyNullTestAPI",
        "toys",
        "Foo",
        ImmutableMap.of(
            "toys.Foo:void test(java.lang.String, java.lang.String, java.lang.String)",
            Sets.newHashSet(0, 1)),
        "import com.google.common.base.Preconditions;",
        "import java.util.Objects;",
        "import org.junit.Assert;",
        "class Foo {",
        "  private String foo;",
        "  public Foo(String str) {",
        "    if (str == null) str = \"foo\";",
        "    this.foo = str;",
        "  }",
        "  public void test(String s, String t, String u) {",
        "    if (s.length() >= 5) {",
        "      t.toString();",
        "      t = s;",
        "    } else {",
        "      Preconditions.checkNotNull(t);",
        "      u = t;",
        "    }",
        "    Objects.requireNonNull(u);",
        "  }",
        "}");
  }

  @Test
  public void toyConditionalFlow2() throws Exception {
    testTemplate(
        "toyNullTestAPI",
        "toys",
        "Foo",
        ImmutableMap.of(
            "toys.Foo:void test(java.lang.Object, java.lang.Object, java.lang.Object, java.lang.Object)",
            Sets.newHashSet(0, 3)),
        "import com.google.common.base.Preconditions;",
        "import java.util.Objects;",
        "import org.junit.Assert;",
        "class Foo {",
        "  private String foo;",
        "  public Foo(String str) {",
        "    if (str == null) str = \"foo\";",
        "    this.foo = str;",
        "  }",
        "  public void test(Object a, Object b, Object c, Object d) {",
        "    if (a != null) {",
        "      b.toString();",
        "      d.toString();",
        "    } else {",
        "      Preconditions.checkNotNull(c);",
        "    }",
        "    Objects.requireNonNull(a);",
        "    if (b != null) {",
        "      c.toString();",
        "      d.toString();",
        "    } else {",
        "      Preconditions.checkNotNull(b);",
        "       if (c != null) {",
        "          d.toString();",
        "       } else {",
        "          Preconditions.checkNotNull(d);",
        "       }",
        "    }",
        "  }",
        "}");
  }

  @Test
  public void toyReassigningTest() throws Exception {
    testTemplate(
        "toyNullTestAPI",
        "toys",
        "Foo",
        ImmutableMap.of(
            "toys.Foo:void test(java.lang.String, java.lang.String)", Sets.newHashSet(0)),
        "import com.google.common.base.Preconditions;",
        "import java.util.Objects;",
        "import org.junit.Assert;",
        "class Foo {",
        "  private String foo;",
        "  public Foo(String str) {",
        "    if (str == null) str = \"foo\";",
        "    this.foo = str;",
        "  }",
        "  public void test(String s, String t) {",
        "    Preconditions.checkNotNull(s);",
        "    if (t == null) {",
        "      t = s;",
        "    }",
        "    Objects.requireNonNull(t);",
        "  }",
        "}");
  }

  @Test
  public void testObjectArray() throws Exception {
    testTemplate(
        "testObjectArray",
        "arrays",
        "TestArray",
        ImmutableMap.of(
            "arrays.TestArray:java.lang.String foo(java.lang.Object[])", Sets.newHashSet(0)),
        "class TestArray {",
        "  public static String foo(Object[] o) {",
        "    return o.toString();",
        "  }",
        "}");
  }

  @Test
  public void testGenericMethod() throws Exception {
    testTemplate(
        "testGenericMethod",
        "generic",
        "TestGeneric",
        ImmutableMap.of("generic.TestGeneric:java.lang.String foo(T)", Sets.newHashSet(0)),
        "public class TestGeneric<T> {",
        "  public String foo(T t) {",
        "    return t.toString();",
        "  }",
        "  public static void main(String arg[]) {",
        "    TestGeneric<String> tg = new TestGeneric<String>();",
        "    System.out.println(tg.foo(\"generic test\"));",
        "  }",
        "}");
  }

  @Test
  public void testMethodWithGenericParameter() throws Exception {
    testTemplate(
        "testMethodWithGenericParameter",
        "generic",
        "TestGeneric",
        ImmutableMap.of(
            "generic.TestGeneric:java.lang.String getString(generic.TestGeneric.Generic<java.lang.String,java.lang.String>)",
            Sets.newHashSet(0)),
        "public class TestGeneric {",
        "  static class Generic<T,U> {",
        "    public String foo(T t) {",
        "      return \"hi\";",
        "    }",
        "  }",
        "  public String getString(Generic<String,String> g) {",
        "    return g.foo(\"test\");",
        "  }",
        "}");
  }

  @Test
  public void nestedGeneric() throws Exception {
    testTemplate(
        "nestedGeneric",
        "generic",
        "TestGeneric",
        ImmutableMap.of(
            "generic.TestGeneric:java.lang.String getString(generic.TestGeneric.Generic<generic.TestGeneric.Generic<java.lang.String>>)",
            Sets.newHashSet(0)),
        "public class TestGeneric {",
        "  static class Generic<T> {",
        "    public String foo(T t) {",
        "      return \"hi\";",
        "    }",
        "  }",
        "  public String getString(Generic<Generic<String>> g) {",
        "    return g.foo(null);",
        "  }",
        "}");
  }

  @Test
  public void wildcards() throws Exception {
    testTemplate(
        "wildcards",
        "generic",
        "TestGeneric",
        ImmutableMap.of(
            "generic.TestGeneric:void genericWildcardLower(generic.TestGeneric.Generic<? super java.lang.String>)",
            Sets.newHashSet(0),
            "generic.TestGeneric:void genericWildcard(generic.TestGeneric.Generic<?>)",
            Sets.newHashSet(0),
            "generic.TestGeneric:java.lang.String genericWildcardUpper(generic.TestGeneric.Generic<? extends java.lang.String>)",
            Sets.newHashSet(0)),
        "public class TestGeneric {",
        "  public abstract static class Generic<T> {",
        "    public String getString(T t) {",
        "      return \"t\";",
        "    }",
        "    public void doNothing() {}",
        "    public abstract T getSomething();",
        "  }",
        "  public static void genericWildcard(Generic<?> g) { g.doNothing(); };",
        "  public static String genericWildcardUpper(Generic<? extends String> g) { return g.getSomething(); };",
        "  public static void genericWildcardLower(Generic<? super String> g) { g.getString(\"hello\"); };",
        "}");
  }

  @Test
  public void multiArgWildcards() throws Exception {
    testTemplate(
        "multiArgWildcards",
        "generic",
        "TestGeneric",
        ImmutableMap.of(
            "generic.TestGeneric:void genericMultiWildcard(java.lang.String, generic.TestGeneric.Generic<?,?>)",
            Sets.newHashSet(1)),
        "public class TestGeneric {",
        "  public abstract static class Generic<T,U> {",
        "    public void doNothing() {}",
        "  }",
        "  public static void genericMultiWildcard(String s, Generic<?,?> g) { g.doNothing(); };",
        "}");
  }

  @Test
  public void nestedWildcard() throws Exception {
    testTemplate(
        "nestedWildcard",
        "generic",
        "TestGeneric",
        ImmutableMap.of(
            "generic.TestGeneric:void nestedWildcard(generic.TestGeneric.Generic<generic.TestGeneric.Generic<?>>)",
            Sets.newHashSet(0)),
        "public class TestGeneric {",
        "  public abstract static class Generic<T> {",
        "    public void doNothing() {}",
        "  }",
        "  public static void nestedWildcard(Generic<Generic<?>> g) { g.doNothing(); };",
        "}");
  }

  @Test
  public void constructors() throws Exception {
    testTemplate(
        "constructors",
        "toys",
        "TestConstructor",
        ImmutableMap.of(
            "toys.TestConstructor:void TestConstructor(java.lang.String)", Sets.newHashSet(0)),
        "public class TestConstructor {",
        "  TestConstructor(String s) {",
        "    s.toString();",
        "  }",
        "}");
  }

  @Test
  public void toyJARAnnotatingClasses() throws Exception {
    testAnnotationInJarTemplate(
        "toyJARAnnotatingClasses",
        "com.uber.nullaway.jarinfer.toys.unannotated",
        "../test-java-lib-jarinfer/build/libs/test-java-lib-jarinfer.jar",
        ImmutableMap.of(
            "Lcom/uber/nullaway/jarinfer/toys/unannotated/ExpectNullable;",
            BytecodeAnnotator.javaxNullableDesc,
            "Lcom/uber/nullaway/jarinfer/toys/unannotated/ExpectNonnull;",
            BytecodeAnnotator.javaxNonnullDesc));
  }

  @Test
  public void toyAARAnnotatingClasses() throws Exception {
    if (System.getProperty("java.version").startsWith("1.8")) {
      // We only build the sample Android apps on JDK 11+
      return;
    }
    testAnnotationInAarTemplate(
        "toyAARAnnotatingClasses",
        "com.uber.nullaway.jarinfer.toys.unannotated",
        "../test-android-lib-jarinfer/build/outputs/aar/test-android-lib-jarinfer-release.aar",
        ImmutableMap.of(
            "Lcom/uber/nullaway/jarinfer/toys/unannotated/ExpectNullable;",
            BytecodeAnnotator.androidNullableDesc,
            "Lcom/uber/nullaway/jarinfer/toys/unannotated/ExpectNonnull;",
            BytecodeAnnotator.androidNonnullDesc));
  }

  @Test
  public void jarinferOutputJarIsBytePerByteDeterministic() throws Exception {
    String jarPath = "../test-java-lib-jarinfer/build/libs/test-java-lib-jarinfer.jar";
    String pkg = "com.uber.nullaway.jarinfer.toys.unannotated";
    DefinitelyDerefedParamsDriver driver = new DefinitelyDerefedParamsDriver();
    driver.run(jarPath, "L" + pkg.replaceAll("\\.", "/"));
    byte[] checksumBytes1 = sha1sum(driver.lastOutPath);
    // Wait a second to ensure system time has changed
    Thread.sleep(1);
    driver.run(jarPath, "L" + pkg.replaceAll("\\.", "/"));
    byte[] checksumBytes2 = sha1sum(driver.lastOutPath);
    Assert.assertArrayEquals(checksumBytes1, checksumBytes2);
  }

  @Test
  public void testSignedJars() throws Exception {
    // Set test configuration paths / options
    String baseJarPath = "../test-java-lib-jarinfer/build/libs/test-java-lib-jarinfer.jar";
    String pkg = "com.uber.nullaway.jarinfer.toys.unannotated";
    String baseJarName = FilenameUtils.getBaseName(baseJarPath);
    String workingFolderPath = outputFolder.newFolder("signed_" + pkg).getAbsolutePath();
    String inputJarPath = workingFolderPath + "/" + baseJarName + ".jar";
    String outputJarPath = workingFolderPath + "/" + baseJarName + "-annotated.jar";

    copyAndSignJar(baseJarPath, inputJarPath);

    // Test that this new jar fails if not run in --strip-jar-signatures mode
    boolean signedJarExceptionThrown = false;
    try {
      DefinitelyDerefedParamsDriver driver1 = new DefinitelyDerefedParamsDriver();
      driver1.runAndAnnotate(inputJarPath, "", outputJarPath);
    } catch (SignedJarException sje) {
      signedJarExceptionThrown = true;
    }
    Assert.assertTrue(signedJarExceptionThrown);

    // And that it succeeds if run in --strip-jar-signatures mode
    DefinitelyDerefedParamsDriver driver2 = new DefinitelyDerefedParamsDriver();
    driver2.runAndAnnotate(inputJarPath, "", outputJarPath, true);

    Assert.assertTrue(
        "Annotated jar after signature stripping does not match the expected jar!",
        AnnotationChecker.checkMethodAnnotationsInJar(
            outputJarPath,
            ImmutableMap.of(
                "Lcom/uber/nullaway/jarinfer/toys/unannotated/ExpectNullable;",
                BytecodeAnnotator.javaxNullableDesc,
                "Lcom/uber/nullaway/jarinfer/toys/unannotated/ExpectNonnull;",
                BytecodeAnnotator.javaxNonnullDesc)));
    // Files should match the base jar, not the one to which we added META-INF, since we are
    // stripping those files
    Assert.assertTrue(
        "Annotated jar after signature stripping does not have all the entries present in the input "
            + "jar (before signing), or contains extra (e.g. META-INF) entries!",
        EntriesComparator.compareEntriesInJars(outputJarPath, baseJarPath));
    // Check that META-INF/Manifest.MF is content-identical to the original unsigned jar
    Assert.assertTrue(
        "Annotated jar after signature stripping does not preserve the info inside META-INF/MANIFEST.MF",
        EntriesComparator.compareManifestContents(outputJarPath, baseJarPath));
  }

  /** copy the jar at {@code baseJarPath} to a signed jar at {@code signedJarPath} */
  private void copyAndSignJar(String baseJarPath, String signedJarPath)
      throws CertificateException,
          KeyStoreException,
          IOException,
          NoSuchAlgorithmException,
          UnrecoverableEntryException {
    String ksPath =
        Thread.currentThread().getContextClassLoader().getResource("testKeyStore.jks").getPath();
    var ksPassword = "testPassword".toCharArray();
    var keystore = KeyStore.getInstance(new File(ksPath), ksPassword);
    var protParam = new KeyStore.PasswordProtection(ksPassword);
    var pkEntry = (KeyStore.PrivateKeyEntry) keystore.getEntry("testKeystore", protParam);

    JarSigner signer = new JarSigner.Builder(pkEntry).digestAlgorithm("SHA-256").build();
    try (ZipFile in = new ZipFile(baseJarPath);
        FileOutputStream out = new FileOutputStream(signedJarPath)) {
      signer.sign(in, out);
    }
  }

  private byte[] sha1sum(String path) throws Exception {
    File file = new File(path);
    MessageDigest digest = MessageDigest.getInstance("SHA-1");
    InputStream fis = new FileInputStream(file);
    int n = 0;
    byte[] buffer = new byte[8192];
    while (n != -1) {
      n = fis.read(buffer);
      if (n > 0) {
        digest.update(buffer, 0, n);
      }
    }
    fis.close();
    return digest.digest();
  }
}
