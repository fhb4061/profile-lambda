# Profile manager lambda function

## What is this?

> This is an experimental Java lambda function for me to learn to use AWS Lambda functions. The function will manage profiles in a DynamoDB.
> 
> More will be added later as I flesh things out

### Prerequisite
- JDK 25
- Gradle
- Docker (optional)

### Local Docker build and run
`./gradlew build -x test`

`docker build -t image-name:image-tag .`

`docker run -p 9000:8080 image-name:image-tag`

```
Test

curl "http://localhost:9000/2015-03-31/functions/function/invocations" -d '{}'
```