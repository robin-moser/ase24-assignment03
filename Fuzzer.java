import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.Random;

public class Fuzzer {
    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: java Fuzzer.java \"<command_to_fuzz>\"");
            System.exit(1);
        }
        String commandToFuzz = args[0];
        String workingDirectory = "./";

        if (!Files.exists(Paths.get(workingDirectory, commandToFuzz))) {
            throw new RuntimeException("Could not find command '%s'.".formatted(commandToFuzz));
        }

        String seedInput = "<tag attribute=\"value\">content</tag>";

        ProcessBuilder builder = getProcessBuilderForCommand(commandToFuzz, workingDirectory);
        System.out.printf("Command: %s\n", builder.command());

        runCommand(builder, seedInput, getMutatedInputs(seedInput, List.of(
            // insert random characters
            input -> insertRandomCharacters(input, 10, 0x20, 0x7F), // ASCII
            input -> insertRandomCharacters(input, 10, 0, 0x1F), // Control characters
            input -> insertRandomCharacters(input, 10, 0x80, 0xFF ), // All other UTF8

            // Large tag name
            input -> input.replace("tag", randomString(50)),
            // Large attribute value
            input -> input.replace("value", randomString(50)),
            // Large attribute name
            input -> input.replace("attribute", randomString(50)),

            // Replace html control characters
            input -> input.replace("<", "<<"),
            input -> input.replace(">", ">>"),
            input -> input.replace(">", "\\>"),
            input -> input.replace("<", "\\<"),
            input -> input.replace(" ", "\t"),
            input -> input.replace("<", "\0<"),
            input -> input.replace("\"", "\'"),
            input -> input.replace("\"", "`"),

            // Replace first html control character
            input -> input.replaceFirst("\"", "\'"),
            input -> input.replaceFirst("\"", "`"),
            input -> input.replaceFirst("\"", "\0"),
            input -> input.replaceFirst("\"", "\t"),
            input -> input.replaceFirst("<", "\0"),
            input -> input.replaceFirst("<", "\t"),
            input -> input.replaceFirst(">", "\0"),

            // Duplicate HTML tags
            input -> duplicateTags(input),
            // Create deeply nested elements
            input -> createNestedElements(20),
            // Delete random elements
            input -> deleteElements(input),
            // Inject invalid CSS and script tags
            input -> cssAndScriptMutation(input)

        )));
    }

    private static ProcessBuilder getProcessBuilderForCommand(String command, String workingDirectory) {
        ProcessBuilder builder = new ProcessBuilder();
        boolean isWindows = System.getProperty("os.name").toLowerCase().startsWith("windows");
        if (isWindows) {
            builder.command("cmd.exe", "/c", command);
        } else {
            builder.command("sh", "-c", command);
        }
        builder.directory(new File(workingDirectory));
        builder.redirectErrorStream(true); // redirect stderr to stdout
        return builder;
    }

    private static void runCommand(ProcessBuilder builder, String seedInput, List<String> mutatedInputs) {
        Stream.concat(Stream.of(seedInput), mutatedInputs.stream()).forEach(
            input -> {


                try {

                    Process process = builder.start();

                    System.out.printf("Input: %s\n", input);
                    OutputStream streamToCommand = process.getOutputStream();
                    streamToCommand.write(input.getBytes());
                    streamToCommand.flush();
                    streamToCommand.close();

                    int exitCode = process.waitFor();
                    System.out.printf("Exit code: %s\n", exitCode);

                    InputStream streamFromCommand = process.getInputStream();
                    String output = readStreamIntoString(streamFromCommand);
                    streamFromCommand.close();


                    if (exitCode != 0) {
                        System.out.println("Program input:");
                        System.out.println(input);
                        System.out.println("Program output:");
                        // indent the output with 4 spaces
                        System.out.println(output.trim().replaceAll("(?m)^", "    ") + "\n\n");
                    }

                } catch (Exception e) {
                    System.out.println("Exception:");
                    e.printStackTrace();
                }
            }
        );
    }

    private static String readStreamIntoString(InputStream inputStream) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        return reader.lines()
            .map(line -> line + System.lineSeparator())
            .collect(Collectors.joining());
    }

    private static List<String> getMutatedInputs(String seedInput, Collection<Function<String, String>> mutators) {
        return mutators.stream()
            .map(mutator -> mutator.apply(seedInput))
            .collect(Collectors.toList());
    }

    /// Mutators

    private static final Random random = new Random();

    private static String insertRandomCharacters(String input, int count, int start, int end) {
        Random random = new Random();
        for (int i = 0; i < count; i++) {
            int randpos = random.nextInt(input.length());
            char randchar = (char) (random.nextInt(end - start) + start);
            input = input.substring(0, randpos) + randchar + input.substring(randpos);
        }
        return input;
    }

    private static String duplicateTags(String input) {
        return input.replaceAll("<([^/][^>]+)>", "<$1><$1>");
    }

    private static String createNestedElements(int depth) {
        String nested = "";
        for (int i = 0; i < depth; i++) {
            nested += "<div>";
        }
        nested += "content";
        for (int i = 0; i < depth; i++) {
            nested += "</div>";
        }
        return nested.toString();
    }

    private static String deleteElements(String input) {
        if (input.isEmpty()) return input;
        int start = random.nextInt(input.length());
        int end = random.nextInt(input.length() - start) + start;
        return input.substring(0, start) + input.substring(end);
    }

    private static String cssAndScriptMutation(String input) {
        return input + "<style>body{background-color:#" + Integer.toHexString(random.nextInt(0xFFFFFF)) + ";}</style>";
    }

    private static String randomString(int length) {
        char[] chars = new char[length];
        for (int i = 0; i < length; i++) {
            // only chars from a-z
            chars[i] = (char) (random.nextInt(26) + 'a');
        }
        return new String(chars);
    }

}
