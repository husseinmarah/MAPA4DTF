package milo.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HomeController {

    @GetMapping("/")
    public String home() {
        return "Backend API is running correctly! \n\nPlease access the Frontend UI at: http://localhost:3000";
    }
}
