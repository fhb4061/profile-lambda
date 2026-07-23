# profile-be

Personal learning project (Tane, github: fhb4061): Java 25 AWS Lambda managing user profiles in DynamoDB, deployed as a container image. Explicitly experimental — favor simple/idiomatic code over enterprise patterns; keep the existing minimal style (no framework, direct AWS SDK calls, small handler classes).

## Architecture

Single Docker image (`public.ecr.aws/lambda/java:25`), two handlers sharing one codebase, selected via Lambda CMD override:

- `com.profile.ProfileApiHandler` — API Gateway + Cognito authorizer backend.
  - `GET /profile`, `PUT /profile` — caller's own profile
  - `GET /profiles` — paginated public listing
  - `GET /profiles/{sub}` — public view of one profile
  - Caller identity always comes from the verified JWT `sub` claim (never body/path) — deliberate security choice.
  - Public fields are allowlisted (`sub`, `givenName`, `familyName`); email etc. stays private.
- `com.profile.PostConfirmationHandler` — Cognito post-confirmation trigger. Creates the DynamoDB profile row on sign-up. Guards against also firing on password-reset confirmation.

Build: Gradle (`build.gradle`), plain `java` plugin, AWS SDK v2 for DynamoDB, aws-lambda-java-core/events, Jackson. Tests use an `InMemoryDynamoDb` test double instead of mocks (JUnit 5).

## Infra

Actual AWS infra (CDK stacks `BackendStack`, `CognitoStack`) lives **outside this repo** — this repo only builds/pushes the container image.

`DockerImageCode.fromEcr` pins the image digest at CDK-deploy time, so pushing to ECR alone changes nothing on its own — CI (`.github/workflows/erc-create-image.yml`, triggers on push to `master`) only builds/tags/pushes the image; it deliberately does **not** update the Lambda functions. Tane updates both functions to the new image manually for now (was previously automated via `aws lambda update-function-code`, removed on purpose).

CI flow: build+test with Gradle → assume AWS role via OIDC (`github-action-role`, region `ap-southeast-2`) → build/tag/push image to ECR repo `profile-backend-lambda`.

If you rename a handler class or change the image's CMD, update the `Dockerfile`.

## Branching

Short feature branches merged via PR (e.g. `github-actions`, `read-me`, `lambda-fn`, `push-image-only`).
