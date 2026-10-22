# GitHub repository

The project is hosted in the public repository `bhashitva/bhash`.

```bash
git clone https://github.com/bhashitva/bhash.git
cd bhash
mvn clean verify
java -jar target/bhash-compiler-0.1.0-SNAPSHOT.jar check examples/hello.bhash
```

Authenticate with your GitHub account using your preferred Git client.
The `main` branch contains the JVM compiler and interactive factorial example. GitHub Actions runs
its build, tests and packaged CLI checks on pushes and pull requests.
