FROM public.ecr.aws/lambda/java:25

# Function classes and runtime dependencies from the Gradle build
COPY build/classes/java/main ${LAMBDA_TASK_ROOT}
COPY build/dependencies ${LAMBDA_TASK_ROOT}/lib

# Default handler: API monolith. The post-confirmation trigger and the photo
# validation trigger use the same image with CMD overrides of
# "com.profile.PostConfirmationHandler::handleRequest" and
# "com.profile.PhotoValidationHandler::handleRequest" respectively.
CMD [ "com.profile.ProfileApiHandler::handleRequest" ]
