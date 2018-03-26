// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package build.bazel.tests.integration;

import org.hamcrest.Description;
import org.hamcrest.SelfDescribing;
import org.hamcrest.TypeSafeDiagnosingMatcher;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;
import static com.spotify.hamcrest.optional.OptionalMatchers.emptyOptional;
import static com.spotify.hamcrest.optional.OptionalMatchers.optionalWithValue;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** {@link BazelBaseTestCase}Test */
// suppress since same parameter value is ok for tests readability, tests should encapsulate and not
// hide
@SuppressWarnings("SameParameterValue")
public final class BazelBaseTestCaseTest extends BazelBaseTestCase {

  private static final String WORKSPACE_NAME =
      "workspace(name = 'build_bazel_integration_testing')";

  @Test
  public void testVersion() throws Exception {
    Command cmd = bazel("info", "release");
    assertEquals(0, cmd.run());
    assertThat(cmd.getOutputLines()).contains("release " + System.getProperty("bazel.version"));
  }

  @Test
  public void testTestSuiteExists() throws Exception {
    loadIntegrationTestRuleIntoWorkspace();
    setupPassingTest("IntegrationTestSuiteTest");

    Command cmd = bazel("test", "//:IntegrationTestSuiteTest");
    final int exitCode = cmd.run();

    org.hamcrest.MatcherAssert.assertThat(exitCode, is(successfulExitCode(cmd)));
  }

  @Test
  public void scratchFileShouldCreateFileAndWorkspaceContentsContainThatFile() throws IOException {
    String content = "somecontent";
    String path = "somePath";

    scratchFile(path, content);

    Optional<String> actualScratchFileContent = findPath(workspaceContents(), path).map(this::readFileContent);
    org.hamcrest.MatcherAssert.assertThat(actualScratchFileContent, is(optionalWithValue(equalTo(content))));
  }

  @Test
  public void scratchExecutableFileShouldCreateAnExecutableFile() throws IOException {
    String path = "someExecutablePath";

    scratchExecutableFile(path);

    Optional<Boolean> isExecutable = findPath(workspaceContents(), path).map(this::isExecutable);
    org.hamcrest.MatcherAssert.assertThat(isExecutable, is(optionalWithValue(equalTo(true))));
  }

  @Test
  public void getRunfileReturnTheFile() {
      File runfile = getRunfile("build_bazel_integration_testing", "tools", "BUILD");

      assertTrue("runfile should exists", runfile.exists());
  }

  @Test
  public void newWorkspaceCreatesANewCleanWorkspace() throws IOException {
      String path = "somePathForNewWorkspace";
      scratchFile(path, "somecontent");

      newWorkspace();

      Optional<String> fullPath = findPath(workspaceContents(), path);
      org.hamcrest.MatcherAssert.assertThat(fullPath, is(emptyOptional()));
  }


  private Boolean isExecutable(String path) {
    return Files.isExecutable(Paths.get(path));
  }

  private String readFileContent(String path) {
      try {
          return new String(Files.readAllBytes(Paths.get(path)));
      } catch (IOException e) {
          throw new RuntimeException(e);
      }
  }

  private Optional<String> findPath(List<String> paths, String path) {
    return paths.stream().filter(x -> x.endsWith(path)).findFirst();
  }


  private TypeSafeDiagnosingMatcher<Integer> successfulExitCode(
      final Command cmd) {
    return new TypeSafeDiagnosingMatcher<Integer>() {
      @Override
      protected boolean matchesSafely(
          final Integer exitCode, final Description mismatchDescription) {
        if (exitCode != 0) {
          mismatchDescription
              .appendText(" exit code was ")
              .appendValue(exitCode)
              .appendText("\n")
              .appendText("Workspace contents: \n")
              .appendValueList("", "\n", "\n", workspaceContents())
              .appendDescriptionOf(commandDescription(cmd));
          return false;
        }
        return true;
      }

      @Override
      public void describeTo(final Description description) {
        description.appendText("successful exit code (0)");
      }
    };
  }

  private SelfDescribing commandDescription(final Command cmd) {
    return description -> {
      final String newLine = System.getProperty("line.separator");
      final List<String> logContents =
          logsOfInternalTests(cmd.getErrorLines()).collect(Collectors.toList());
      description
          .appendText("std-error:\n")
          .appendValueList("", newLine, newLine, cmd.getErrorLines());
      if (!logContents.isEmpty()) {
        description
            .appendText("Contents of internal test logs:\n")
            .appendText("*******************************\n")
            .appendValueList(newLine, newLine, newLine, logContents);
      }
    };
  }

  private Stream<String> logsOfInternalTests(final List<String> errorLines) {
    return errorLines
        .stream()
        .filter(line -> line.contains("(see "))
        .map(line -> line.split("see ")[1].replace(")", ""))
        .map(Paths::get)
        .map(
            logPath -> {
              try {
                LinkedList<String> logContents = new LinkedList<>(Files.readAllLines(logPath));
                logContents.addFirst("Log contents:");
                logContents.addFirst(logPath.toString());
                logContents.addFirst("Log path:");
                return logContents;
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            })
        .flatMap(Collection::stream);
  }

  private void setupPassingTest(final String testName) throws IOException {
    writePassingTestJavaSource(testName);
    writeTestBuildFile(testName);
  }

  private void writeTestBuildFile(final String testName) throws IOException {
    scratchFile(
        "BUILD",
        "load('//:bazel_integration_test.bzl', 'bazel_java_integration_test')",
        "",
        "bazel_java_integration_test(",
        "    name = '" + testName + "',",
        "    test_class = '" + testName + "',",
        "    srcs = ['" + testName + ".java'],",
        // inside the sandbox we don't have access to full bazel
        // and we don't need it since it's prepared in advance for us
        "    add_bazel_data_dependency = False,",
        ")");
  }

  private void loadIntegrationTestRuleIntoWorkspace() throws IOException {
    setupRuleSkylarkFiles();
    setupRuleCode();
    scratchFile("./WORKSPACE",WORKSPACE_NAME);
  }

  private void setupRuleCode() throws IOException {
    copyFromRunfiles(
        "build_bazel_integration_testing/java/build/bazel/tests/integration/libworkspace_driver.jar",
        "java/build/bazel/tests/integration/libworkspace_driver.jar");
    scratchFile(
        "java/build/bazel/tests/integration/BUILD.bazel",
        "java_import(",
        "    name = 'workspace_driver',",
        "    jars = ['libworkspace_driver.jar'],",
        "    visibility = ['//visibility:public']",
        ")");
  }

  private void setupRuleSkylarkFiles() throws IOException {
    copyFromRunfiles(
        "build_bazel_integration_testing/bazel_integration_test.bzl", "bazel_integration_test.bzl");
    copyFromRunfiles(
        "build_bazel_integration_testing/tools/bazel_hash_dict.bzl", "tools/bazel_hash_dict.bzl");
    copyFromRunfiles(
        "build_bazel_integration_testing/tools/bazel_java_integration_test.bzl",
        "tools/bazel_java_integration_test.bzl");
    copyFromRunfiles(
        "build_bazel_integration_testing/tools/bazel_py_integration_test.bzl",
        "tools/bazel_py_integration_test.bzl");
    copyFromRunfiles("build_bazel_integration_testing/tools/BUILD", "tools/BUILD");
    copyFromRunfiles("build_bazel_integration_testing/tools/common.bzl", "tools/common.bzl");
    copyFromRunfiles("build_bazel_integration_testing/tools/bazel.sh", "tools/bazel.sh");
    copyFromRunfiles(
        "build_bazel_integration_testing/tools/repositories.bzl", "tools/repositories.bzl");
    scratchFile(
        "go/bazel_integration_test.bzl",
        "RULES_GO_COMPATIBLE_BAZEL_VERSION = []\n"
            + "def bazel_go_integration_test(name, srcs, deps=[], versions=RULES_GO_COMPATIBLE_BAZEL_VERSION, **kwargs):\n"
            + "  pass");
    // In order to make //go a package it must have a build file (even if it's empty).
    scratchFile("go/BUILD.bazel", "");
  }

  private void writePassingTestJavaSource(final String testName) throws IOException {
    scratchFile("" + testName + ".java", somePassingTestNamed(testName));
  }

  private List<String> somePassingTestNamed(final String testName) {
    return Arrays.asList(
        "import org.junit.Test;",
        "public class " + testName + " {",
        " @Test",
        " public void testSuccess() {",
        "  }",
        "}");
  }
}
