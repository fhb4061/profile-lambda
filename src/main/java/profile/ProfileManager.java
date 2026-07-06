package profile;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

public class ProfileManager implements RequestHandler<Object, String> {

    @Override
    public String handleRequest(Object profile, Context context) {
        return "Hello World";
    }
}
