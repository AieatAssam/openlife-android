import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates the repository plan without adding a runtime dependency to Android modules. */
public final class PlanCheck {
  private static final List<String> PLAN_KEYS = List.of(
      "schema_version", "plan_version", "plan_date", "baseline_commit",
      "governing_documents", "ethos", "conventions", "release_train",
      "decisions_made_by_this_plan", "phases", "owner_actions_required",
      "review_findings_index");
  private static final List<String> PHASE_KEYS = List.of(
      "id", "title", "goal", "exit_gate", "steps");
  private static final List<String> MASTER_STEP_KEYS = List.of(
      "id", "title", "file", "status", "depends_on", "estimate", "owner");
  private static final List<String> STEP_KEYS = List.of(
      "id", "title", "phase", "status", "depends_on", "estimate", "owner",
      "summary", "requirements", "tdd", "verification", "acceptance_criteria",
      "docs_to_update", "risks", "rollback");
  private static final List<String> STATUSES = List.of(
      "todo", "in_progress", "blocked", "review", "done", "waived");

  private final Path root;
  private final boolean json;
  private final Yaml yaml;
  private final List<String> errors = new ArrayList<>();
  private final List<String> warnings = new ArrayList<>();
  private final LinkedHashMap<String, StepRef> steps = new LinkedHashMap<>();
  private final Map<String, Object> plan = new LinkedHashMap<>();

  private PlanCheck(Path root, boolean json) {
    this.root = root.toAbsolutePath().normalize();
    this.json = json;
    LoaderOptions loaderOptions = new LoaderOptions();
    loaderOptions.setAllowDuplicateKeys(false);
    loaderOptions.setWarnOnDuplicateKeys(false);
    this.yaml = new Yaml(loaderOptions);
  }

  public static void main(String[] args) {
    boolean json = false;
    String rootArg = ".";
    for (String arg : args) {
      if ("--json".equals(arg)) {
        json = true;
      } else if (arg.startsWith("--")) {
        System.err.println("unknown option: " + arg);
        System.exit(2);
      } else if (".".equals(rootArg)) {
        rootArg = arg;
      } else {
        System.err.println("expected one plan directory, got: " + arg);
        System.exit(2);
      }
    }

    PlanCheck checker = new PlanCheck(Paths.get(rootArg), json);
    checker.run();
    checker.printReport();
    System.exit(checker.errors.isEmpty() ? 0 : 1);
  }

  private void run() {
    Path planFile = root.resolve("plan.yaml");
    Object document = load(planFile);
    if (!(document instanceof Map<?, ?>)) {
      error(planFile, "document", "must be a YAML map");
      return;
    }
    for (String key : PLAN_KEYS) {
      if (!((Map<?, ?>) document).containsKey(key)) {
        error(planFile, key, "missing key");
      }
    }
    copyStringKeyedMap(document, plan);
    validatePlanTypes(planFile);
    collectPlanSteps(planFile);
    validateReferencedFiles();
    validateAllStepFiles();
    validateDependencies();
    validateOwnerActions();
    validateFindings();
  }

  private void validatePlanTypes(Path planFile) {
    requireType(plan, "schema_version", Number.class, planFile, "schema_version");
    requireType(plan, "plan_version", String.class, planFile, "plan_version");
    Object planDate = plan.get("plan_date");
    if (plan.containsKey("plan_date") && !(planDate instanceof Date) && !(planDate instanceof String)) {
      error(planFile, "plan_date", "must be a date or string");
    }
    requireType(plan, "governing_documents", List.class, planFile, "governing_documents");
    requireType(plan, "ethos", Map.class, planFile, "ethos");
    requireType(plan, "conventions", Map.class, planFile, "conventions");
    requireType(plan, "release_train", List.class, planFile, "release_train");
    requireType(plan, "decisions_made_by_this_plan", List.class, planFile, "decisions_made_by_this_plan");
    requireType(plan, "phases", List.class, planFile, "phases");
    requireType(plan, "owner_actions_required", List.class, planFile, "owner_actions_required");
    requireType(plan, "review_findings_index", List.class, planFile, "review_findings_index");
  }

  private void collectPlanSteps(Path planFile) {
    Object phasesValue = plan.get("phases");
    if (!(phasesValue instanceof List<?> phases)) {
      error(planFile, "phases", "must be a list");
      return;
    }
    Set<String> phaseIds = new HashSet<>();
    for (Object phaseValue : phases) {
      if (!(phaseValue instanceof Map<?, ?> phase)) {
        error(planFile, "phases[]", "must contain maps");
        continue;
      }
      String phaseId = string(phase.get("id"));
      String phasePath = phaseId == null ? "phases[]" : "phases[" + phaseId + "]";
      for (String key : PHASE_KEYS) {
        if (!phase.containsKey(key)) {
          error(planFile, phasePath + "." + key, "missing key");
        }
      }
      requireType(phase, "id", String.class, planFile, phasePath + ".id");
      requireType(phase, "title", String.class, planFile, phasePath + ".title");
      requireType(phase, "goal", String.class, planFile, phasePath + ".goal");
      requireType(phase, "exit_gate", String.class, planFile, phasePath + ".exit_gate");
      requireType(phase, "steps", List.class, planFile, phasePath + ".steps");
      if (phaseId == null) {
        error(planFile, "phases[].id", "missing or not a string");
        continue;
      }
      if (!phaseIds.add(phaseId)) {
        error(planFile, "phases[].id", "duplicate phase " + phaseId);
      }
      Object phaseStepsValue = phase.get("steps");
      if (!(phaseStepsValue instanceof List<?> phaseSteps)) {
        error(planFile, "phases[" + phaseId + "].steps", "must be a list");
        continue;
      }
      for (Object stepValue : phaseSteps) {
        if (!(stepValue instanceof Map<?, ?> step)) {
          error(planFile, "phases[" + phaseId + "].steps[]", "must contain maps");
          continue;
        }
        String id = string(step.get("id"));
        String stepPath = id == null ? "step <unknown>" : "step " + id;
        for (String key : MASTER_STEP_KEYS) {
          if (!step.containsKey(key)) {
            error(planFile, stepPath + "." + key, "missing key");
          }
        }
        requireType(step, "id", String.class, planFile, stepPath + ".id");
        requireType(step, "title", String.class, planFile, stepPath + ".title");
        requireType(step, "file", String.class, planFile, stepPath + ".file");
        requireType(step, "status", String.class, planFile, stepPath + ".status");
        requireType(step, "depends_on", List.class, planFile, stepPath + ".depends_on");
        requireType(step, "estimate", String.class, planFile, stepPath + ".estimate");
        requireType(step, "owner", String.class, planFile, stepPath + ".owner");
        if (id == null) {
          error(planFile, "phases[" + phaseId + "].steps[].id", "missing or not a string");
          continue;
        }
        if (!id.matches("[A-Z][0-9]+-[0-9]{2}")) {
          error(planFile, "step " + id + ".id", "must match <phase>-<nn>");
        }
        if (!id.startsWith(phaseId + "-")) {
          error(planFile, "step " + id + ".id", "phase prefix does not match " + phaseId);
        }
        if (steps.containsKey(id)) {
          error(planFile, "step " + id + ".id", "duplicate step id");
          continue;
        }
        String file = string(step.get("file"));
        if (file == null) {
          error(planFile, "step " + id + ".file", "missing or not a string");
          file = "steps/" + id + ".yaml";
        }
        List<String> dependencies = step.containsKey("depends_on")
            ? stringList(step.get("depends_on"), planFile, "step " + id + ".depends_on")
            : List.of();
        String status = string(step.get("status"));
        if (status != null && !STATUSES.contains(status)) {
          error(planFile, "step " + id + ".status", "must be one of " + STATUSES);
        }
        if ("done".equals(status)) {
          Object evidence = step.get("evidence");
          if (!(evidence instanceof String) || ((String) evidence).isBlank()) {
            error(planFile, "step " + id + ".evidence", "required when status is done");
          }
        }
        steps.put(id, new StepRef(id, phaseId, file, dependencies == null ? List.of() : dependencies, status));
      }
    }
  }

  private void validateReferencedFiles() {
    for (StepRef step : steps.values()) {
      Path file = root.resolve(step.file).normalize();
      if (!file.startsWith(root.resolve("steps").normalize())) {
        error(root.resolve("plan.yaml"), "step " + step.id + ".file", "must stay under steps/");
      }
      if (!step.file.equals("steps/" + step.id + ".yaml")) {
        error(root.resolve("plan.yaml"), "step " + step.id + ".file", "must be steps/" + step.id + ".yaml");
      }
      if (Files.isSymbolicLink(file)) {
        error(file, "file", "symlink is not allowed");
        continue;
      }
      if (!Files.isRegularFile(file)) {
        error(file, "file", "missing step file referenced by plan.yaml");
      }
    }
  }

  private void validateAllStepFiles() {
    Path stepsDir = root.resolve("steps");
    if (!Files.isDirectory(stepsDir)) {
      error(stepsDir, "directory", "missing steps directory");
      return;
    }
    if (Files.isSymbolicLink(stepsDir)) {
      error(stepsDir, "directory", "symlink is not allowed");
      return;
    }
    try (DirectoryStream<Path> files = Files.newDirectoryStream(stepsDir, "*.yaml")) {
      for (Path file : files) {
        if (Files.isSymbolicLink(file)) {
          error(file, "file", "symlink is not allowed");
          continue;
        }
        Object document = load(file);
        if (!(document instanceof Map<?, ?> step)) {
          error(file, "document", "must be a YAML map");
          continue;
        }
        for (String key : STEP_KEYS) {
          if (!step.containsKey(key)) {
            error(file, key, "missing key");
          }
        }
        validateStepTypes(file, step);
        String filenameId = file.getFileName().toString().replaceFirst("\\.yaml$", "");
        String id = string(step.get("id"));
        if (id == null) {
          continue;
        }
        if (!filenameId.equals(id)) {
          error(file, "id", "does not match filename " + filenameId);
        }
        StepRef ref = steps.get(id);
        if (ref == null) {
          error(file, "id", "is not referenced by plan.yaml");
          continue;
        }
        String phase = string(step.get("phase"));
        if (phase != null && !phase.equals(ref.phase)) {
          error(file, "phase", "must be " + ref.phase);
        }
        List<String> fileDeps = stringList(step.get("depends_on"), file, "depends_on");
        if (fileDeps != null && !fileDeps.equals(ref.dependencies)) {
          error(file, "depends_on", "does not match plan.yaml for " + id);
        }
        String status = string(step.get("status"));
        if (status != null && !STATUSES.contains(status)) {
          error(file, "status", "must be one of " + STATUSES);
        }
      }
    } catch (IOException ex) {
      error(stepsDir, "directory", "cannot enumerate step files: " + ex.getMessage());
    }
  }

  private void validateStepTypes(Path file, Map<?, ?> step) {
    String id = string(step.get("id"));
    String path = id == null ? "step" : "step " + id;
    requireType(step, "id", String.class, file, path + ".id");
    requireType(step, "title", String.class, file, path + ".title");
    requireType(step, "phase", String.class, file, path + ".phase");
    requireType(step, "status", String.class, file, path + ".status");
    requireType(step, "depends_on", List.class, file, path + ".depends_on");
    requireType(step, "estimate", String.class, file, path + ".estimate");
    requireType(step, "owner", String.class, file, path + ".owner");
    requireType(step, "summary", String.class, file, path + ".summary");
    requireType(step, "requirements", List.class, file, path + ".requirements");
    requireType(step, "tdd", Map.class, file, path + ".tdd");
    requireType(step, "verification", Map.class, file, path + ".verification");
    requireType(step, "acceptance_criteria", List.class, file, path + ".acceptance_criteria");
    requireType(step, "docs_to_update", List.class, file, path + ".docs_to_update");
    requireType(step, "risks", List.class, file, path + ".risks");
    requireType(step, "rollback", String.class, file, path + ".rollback");
    validateNestedStepSchema(file, path, step);
  }

  private void validateNestedStepSchema(Path file, String path, Map<?, ?> step) {
    Object requirementsValue = step.get("requirements");
    if (requirementsValue instanceof List<?> requirements) {
      for (int index = 0; index < requirements.size(); index++) {
        Object requirementValue = requirements.get(index);
        String requirementPath = path + ".requirements[" + index + "]";
        if (!(requirementValue instanceof Map<?, ?> requirement)) {
          error(file, requirementPath, "must be a map");
          continue;
        }
        requireNestedKey(requirement, "id", file, requirementPath);
        requireNestedKey(requirement, "text", file, requirementPath);
        requireType(requirement, "id", String.class, file, requirementPath + ".id");
        requireType(requirement, "text", String.class, file, requirementPath + ".text");
        String requirementId = string(requirement.get("id"));
        String stepId = string(step.get("id"));
        if (requirementId != null && stepId != null && !requirementId.matches(stepId + "-R[0-9]+")) {
          error(file, requirementPath + ".id", "must start with " + stepId + "-R");
        }
      }
    }

    Object tddValue = step.get("tdd");
    if (tddValue instanceof Map<?, ?> tdd) {
      String tddPath = path + ".tdd";
      requireNestedKey(tdd, "red", file, tddPath);
      requireNestedKey(tdd, "green", file, tddPath);
      requireNestedKey(tdd, "refactor", file, tddPath);
      requireType(tdd, "red", List.class, file, tddPath + ".red");
      requireType(tdd, "green", List.class, file, tddPath + ".green");
      requireType(tdd, "refactor", List.class, file, tddPath + ".refactor");
      validateRedTests(file, tddPath, tdd.get("red"));
      validateStringListItems(file, tddPath + ".green", tdd.get("green"));
      validateStringListItems(file, tddPath + ".refactor", tdd.get("refactor"));
    }

    Object verificationValue = step.get("verification");
    if (verificationValue instanceof Map<?, ?> verification) {
      String verificationPath = path + ".verification";
      requireNestedKey(verification, "commands", file, verificationPath);
      requireNestedKey(verification, "evidence_required", file, verificationPath);
      requireType(verification, "commands", List.class, file, verificationPath + ".commands");
      requireType(verification, "evidence_required", List.class, file, verificationPath + ".evidence_required");
      validateStringListItems(file, verificationPath + ".commands", verification.get("commands"));
      validateStringListItems(file, verificationPath + ".evidence_required", verification.get("evidence_required"));
    }
  }

  private void validateRedTests(Path file, String path, Object value) {
    if (!(value instanceof List<?> tests)) {
      return;
    }
    for (int index = 0; index < tests.size(); index++) {
      Object testValue = tests.get(index);
      String testPath = path + ".red[" + index + "]";
      if (testValue instanceof String) {
        continue;
      }
      if (!(testValue instanceof Map<?, ?> test)) {
        error(file, testPath, "must be a map");
        continue;
      }
      requireNestedKey(test, "name", file, testPath);
      requireType(test, "name", String.class, file, testPath + ".name");
      if (test.containsKey("asserts")
          && !(test.get("asserts") instanceof String)
          && !(test.get("asserts") instanceof List<?>)) {
        error(file, testPath + ".asserts", "must be a string or list");
      }
    }
  }

  private void validateStringListItems(Path file, String path, Object value) {
    if (!(value instanceof List<?> items)) {
      return;
    }
    for (int index = 0; index < items.size(); index++) {
      if (!(items.get(index) instanceof String)) {
        error(file, path + "[" + index + "]", "must be a string");
      }
    }
  }

  private void requireNestedKey(Map<?, ?> map, String key, Path file, String path) {
    if (!map.containsKey(key)) {
      error(file, path + "." + key, "missing key");
    }
  }

  private void validateDependencies() {
    for (StepRef step : steps.values()) {
      for (String dependency : step.dependencies) {
        if (!steps.containsKey(dependency)) {
          error(root.resolve("plan.yaml"), "step " + step.id + ".depends_on", "unknown dependency " + dependency);
        }
      }
    }
    Map<String, Integer> state = new HashMap<>();
    Deque<String> stack = new ArrayDeque<>();
    Set<String> reported = new HashSet<>();
    for (String id : steps.keySet()) {
      findCycle(id, state, stack, reported);
    }
  }

  private void findCycle(String id, Map<String, Integer> state, Deque<String> stack, Set<String> reported) {
    Integer previous = state.get(id);
    if (previous != null) {
      if (previous == 1) {
        List<String> path = new ArrayList<>(stack);
        int start = path.indexOf(id);
        if (start >= 0) {
          path = path.subList(start, path.size());
          path.add(id);
        }
        String cycle = String.join(" -> ", path);
        if (reported.add(cycle)) {
          error(root.resolve("plan.yaml"), "depends_on", "dependency cycle " + cycle);
        }
      }
      return;
    }
    state.put(id, 1);
    stack.addLast(id);
    StepRef step = steps.get(id);
    if (step != null) {
      for (String dependency : step.dependencies) {
        if (steps.containsKey(dependency)) {
          findCycle(dependency, state, stack, reported);
        }
      }
    }
    stack.removeLast();
    state.put(id, 2);
  }

  private void validateFindings() {
    Object findingsValue = plan.get("review_findings_index");
    if (!(findingsValue instanceof List<?> findings)) {
      return;
    }
    for (Object findingValue : findings) {
      if (!(findingValue instanceof Map<?, ?> finding)) {
        error(root.resolve("plan.yaml"), "review_findings_index[]", "must contain maps");
        continue;
      }
      String findingId = findingIdOrUnknown(finding);
      for (String key : List.of("id", "severity", "text", "step")) {
        if (!finding.containsKey(key)) {
          error(root.resolve("plan.yaml"), "finding " + findingId + "." + key, "missing key");
        }
      }
      requireType(finding, "id", String.class, root.resolve("plan.yaml"), "finding " + findingId + ".id");
      requireType(finding, "severity", String.class, root.resolve("plan.yaml"), "finding " + findingId + ".severity");
      requireType(finding, "text", String.class, root.resolve("plan.yaml"), "finding " + findingId + ".text");
      requireType(finding, "step", String.class, root.resolve("plan.yaml"), "finding " + findingId + ".step");
      String normalizedFindingId = string(finding.get("id"));
      if (normalizedFindingId != null && !normalizedFindingId.matches("F-[0-9]+")) {
        error(root.resolve("plan.yaml"), "finding " + normalizedFindingId + ".id", "must match F-<n>");
      }
      String closingSteps = string(finding.get("step"));
      if (closingSteps == null) {
        error(root.resolve("plan.yaml"), "review_findings_index[].step", "missing or not a string");
        continue;
      }
      for (String step : closingSteps.split(",")) {
        if (!steps.containsKey(step.trim())) {
          error(root.resolve("plan.yaml"), "finding " + findingId + ".step", "unknown step " + step.trim());
        }
      }
    }
  }

  private void validateOwnerActions() {
    Object actionsValue = plan.get("owner_actions_required");
    if (!(actionsValue instanceof List<?> actions)) {
      return;
    }
    for (Object actionValue : actions) {
      if (!(actionValue instanceof Map<?, ?> action)) {
        error(root.resolve("plan.yaml"), "owner_actions_required[]", "must contain maps");
        continue;
      }
      String actionId = string(action.get("id"));
      String actionPath = actionId == null ? "owner action <unknown>" : "owner action " + actionId;
      for (String key : List.of("id", "text", "step")) {
        if (!action.containsKey(key)) {
          error(root.resolve("plan.yaml"), actionPath + "." + key, "missing key");
        }
      }
      requireType(action, "id", String.class, root.resolve("plan.yaml"), actionPath + ".id");
      requireType(action, "text", String.class, root.resolve("plan.yaml"), actionPath + ".text");
      requireType(action, "step", String.class, root.resolve("plan.yaml"), actionPath + ".step");
      if (actionId != null && !actionId.matches("OA-[0-9]+")) {
        error(root.resolve("plan.yaml"), actionPath + ".id", "must match OA-<n>");
      }
      String closingSteps = string(action.get("step"));
      if (closingSteps == null) {
        continue;
      }
      for (String step : closingSteps.split(",")) {
        if (!steps.containsKey(step.trim())) {
          error(root.resolve("plan.yaml"), actionPath + ".step", "unknown step " + step.trim());
        }
      }
    }
  }

  private static String findingIdOrUnknown(Map<?, ?> finding) {
    String id = string(finding.get("id"));
    return id == null ? "<unknown>" : id;
  }

  private Object load(Path file) {
    if (!Files.isRegularFile(file)) {
      return null;
    }
    try (InputStream input = Files.newInputStream(file)) {
      return yaml.load(input);
    } catch (Exception ex) {
      error(file, "YAML", firstLine(ex.getMessage()));
      return null;
    }
  }

  private List<String> stringList(Object value, Path file, String key) {
    if (!(value instanceof List<?> list)) {
      error(file, key, "must be a list");
      return null;
    }
    List<String> result = new ArrayList<>();
    for (Object item : list) {
      String itemString = string(item);
      if (itemString == null) {
        error(file, key, "must contain strings");
        return null;
      }
      result.add(itemString);
    }
    return result;
  }

  private static void copyStringKeyedMap(Object value, Map<String, Object> destination) {
    if (!(value instanceof Map<?, ?> map)) {
      return;
    }
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (entry.getKey() instanceof String key) {
        destination.put(key, entry.getValue());
      }
    }
  }

  private static String string(Object value) {
    return value instanceof String ? (String) value : null;
  }

  private void requireType(Map<?, ?> map, String key, Class<?> expected, Path file, String path) {
    if (map.containsKey(key) && !expected.isInstance(map.get(key))) {
      error(file, path, "must be " + expected.getSimpleName());
    }
  }

  private void error(Path file, String key, String message) {
    errors.add(file + ": " + key + ": " + message);
  }

  private void printReport() {
    List<String> runnable = new ArrayList<>();
    for (StepRef step : steps.values()) {
      if ("todo".equals(step.status) && step.dependencies.stream()
          .allMatch(dependency -> steps.containsKey(dependency) && "done".equals(steps.get(dependency).status))) {
        runnable.add(step.id);
      }
    }
    if (json) {
      System.out.println("{\"valid\":" + errors.isEmpty()
          + ",\"steps\":" + steps.size()
          + ",\"errors\":[" + jsonArray(errors)
          + "],\"warnings\":[" + jsonArray(warnings)
          + "],\"runnable\":[" + jsonArray(runnable) + "]}");
      return;
    }
    for (String error : errors) {
      System.out.println("ERR " + error);
    }
    for (String warning : warnings) {
      System.out.println("WARN " + warning);
    }
    System.out.println("steps=" + steps.size() + " errors=" + errors.size());
    System.out.println("runnable now: " + runnable);
  }

  private static String jsonArray(List<String> values) {
    List<String> quoted = new ArrayList<>();
    for (String value : values) {
      quoted.add("\"" + jsonEscape(value) + "\"");
    }
    return String.join(",", quoted);
  }

  private static String jsonEscape(String value) {
    StringBuilder escaped = new StringBuilder();
    for (int i = 0; i < value.length(); i++) {
      char character = value.charAt(i);
      switch (character) {
        case '\\' -> escaped.append("\\\\");
        case '"' -> escaped.append("\\\"");
        case '\b' -> escaped.append("\\b");
        case '\f' -> escaped.append("\\f");
        case '\n' -> escaped.append("\\n");
        case '\r' -> escaped.append("\\r");
        case '\t' -> escaped.append("\\t");
        default -> {
          if (character < 0x20) {
            escaped.append(String.format("\\u%04x", (int) character));
          } else {
            escaped.append(character);
          }
        }
      }
    }
    return escaped.toString();
  }

  private static String firstLine(String value) {
    if (value == null) {
      return "unknown parse error";
    }
    return value.split("\\R", 2)[0];
  }

  private static final class StepRef {
    private final String id;
    private final String phase;
    private final String file;
    private final List<String> dependencies;
    private final String status;

    private StepRef(String id, String phase, String file, List<String> dependencies, String status) {
      this.id = id;
      this.phase = phase;
      this.file = file;
      this.dependencies = Collections.unmodifiableList(new ArrayList<>(dependencies));
      this.status = status;
    }
  }
}
