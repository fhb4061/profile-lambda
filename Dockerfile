FROM public.ecr.aws/lambda/java:25

# Copy function code and runtime dependencies from Gradle layout
COPY build/classes/java/main ${LAMBDA_TASK_ROOT}

# Set the CMD to your handler (could also be done as a paraLAMBDA_TASK_ROOTmeter override outside of the Dockerfile)
CMD [ "profile.ProfileManager::handleRequest" ]