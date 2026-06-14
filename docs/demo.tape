# Marshal demo — records docs/images/demo.gif
# Run: vhs docs/demo.tape
# Requires: vhs (https://github.com/charmbracelet/vhs)
#           marshal.jar built and on PATH, or use java -jar marshal.jar

Output docs/images/demo.gif

Set Shell "bash"
Set FontSize 14
Set Width 120
Set Height 36
Set Theme "Dracula"
Set Padding 16

# Generate the demo cache first (idempotent)
Type "java -cp marshal-cli/build/libs/marshal-cli-*.jar dev.marshalhq.cli.DemoCacheBuilder"
Enter
Sleep 1s

# Clear the screen, then run the scan
Type "clear"
Enter
Sleep 300ms

Type "# Scanning a Spring Boot project for supply-chain anomalies..."
Enter
Sleep 500ms

Type "java -jar marshal.jar scan --pom examples/demo/pom.xml --cache-path examples/demo/marshal-cache.db --config examples/demo/marshal.yml"
Sleep 500ms
Enter
Sleep 4s
