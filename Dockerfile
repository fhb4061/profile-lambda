FROM public.ecr.aws/lambda/java:25

# Function classes and runtime dependencies from the Gradle build
COPY build/classes/java/main ${LAMBDA_TASK_ROOT}
COPY build/dependencies ${LAMBDA_TASK_ROOT}/lib

# Default handler: API monolith. The post-confirmation trigger uses the same
# image with a CMD override of "com.profile.PostConfirmationHandler::handleRequest".
CMD [ "com.profile.ProfileApiHandler::handleRequest" ]
