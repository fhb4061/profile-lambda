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

`DockerImageCode.fromEcr` pins the image digest at CDK-deploy time, so pushing to ECR alone changes nothing. CI (`.github/workflows/erc-create-image.yml`, triggers on push to `master`) explicitly runs `aws lambda update-function-code` for both the API function and the post-confirmation trigger function after every ECR push.

CI flow: build+test with Gradle → assume AWS role via OIDC (`github-action-role`, region `ap-southeast-2`) → build/tag/push image to ECR repo `profile-backend-lambda` → update both Lambda functions' code, wait for update.

If you rename a handler class or change the image's CMD, update both the `Dockerfile` and the CI workflow's function-name lookups.

## Branching

Short feature branches merged via PR (e.g. `github-actions`, `read-me`, `lambda-fn`, `push-image-only`).
