// Exercise the production source adapter under Xvfb without replacing its pixel
// or ownership code. Session/lock delivery still needs an interactive logind test.
#define main capture_program_main
#include "x11_capture.cpp"
#undef main

int main(int argc, char** argv) {
    try {
        DisplayOwner owner;
        auto* display = owner.display;
        auto root = DefaultRootWindow(display);
        if ((argc == 4 || argc == 10) && std::string(argv[1]) == "--inspect") {
            auto client = static_cast<Window>(number(argv[2]));
            verifyOwner(display, client, static_cast<pid_t>(number(argv[3])));
            auto frame = frameAncestor(display, client);
            XWindowAttributes clientBounds {}, frameBounds {};
            XGetWindowAttributes(display, client, &clientBounds);
            XGetWindowAttributes(display, frame, &frameBounds);
            int clientX = 0, clientY = 0, frameX = 0, frameY = 0;
            Window child = 0;
            XTranslateCoordinates(display, client, root, 0, 0, &clientX, &clientY, &child);
            XTranslateCoordinates(display, frame, root, 0, 0, &frameX, &frameY, &child);
            std::cout << "Native client bounds=" << clientX << ',' << clientY << ','
                      << clientBounds.width << ',' << clientBounds.height << "; frame bounds="
                      << frameX << ',' << frameY << ',' << frameBounds.width << ',' << frameBounds.height << '\n';
            std::optional<HostWindowGeometry> host;
            if (argc == 10) {
                host = HostWindowGeometry {int(number(argv[4])), int(number(argv[5])), int(number(argv[6])),
                    int(number(argv[7])), int(number(argv[8])), int(number(argv[9]))};
            }
            try {
                auto region = captureRegion(display, client, frame, host);
                std::cout << "Verified frame crop=" << region.x << ',' << region.y << ','
                          << region.width << ',' << region.height << '\n';
            } catch (const std::exception& error) {
                std::cout << "Native crop unavailable: " << error.what() << '\n';
                if (host) throw;
            }
            if (host) {
                WindowStream stream(display, client, static_cast<pid_t>(number(argv[3])), host->width, host->height, host);
                auto pixels = stream.next();
                const int x = host->left + clientBounds.width / 2;
                const int y = host->top + clientBounds.height / 2;
                auto at = 24 + (y * host->width + x) * 4;
                require(pixels[at] == 202 && pixels[at + 1] == 86 && pixels[at + 2] == 18,
                        "AWT content pixel does not align with its local input coordinates");
                auto invalid = *host;
                ++invalid.width;
                bool rejected = false;
                try { captureRegion(display, client, frame, invalid); }
                catch (const std::exception&) { rejected = true; }
                require(rejected, "mismatched AWT/native geometry was admitted");
                std::cout << "Verified AWT source pixels and local pointer-coordinate alignment\n";
            }
            return 0;
        }
        require(argc == 1, "invalid native fixture invocation");
        auto selected = XCreateSimpleWindow(display, root, 0, 0, 80, 60, 0, 0, 0x1256ca);
        auto unrelated = XCreateSimpleWindow(display, root, 0, 0, 80, 60, 0, 0, 0xff00ff);
        XStoreName(display, selected, "Synthetic selected window");
        XStoreName(display, unrelated, "Synthetic unrelated occluder");
        XMapWindow(display, selected);
        XMapRaised(display, unrelated);
        XSync(display, False);
        if (std::getenv("WAYLAND_DISPLAY")) {
            auto deadline = Clock::now() + std::chrono::seconds(3);
            while (frameAncestor(display, selected) == selected && Clock::now() < deadline) {
                std::this_thread::sleep_for(std::chrono::milliseconds(20));
                XSync(display, False);
            }
            require(frameAncestor(display, selected) != selected, "XWayland WM did not decorate the fixture");
        }
        {
            XWindowAttributes frameBounds {};
            auto decorated = frameAncestor(display, selected);
            XGetWindowAttributes(display, decorated, &frameBounds);
            int originX = 0, originY = 0;
            Window child = 0;
            XTranslateCoordinates(display, selected, decorated, 0, 0, &originX, &originY, &child);
            std::cout << "Frame dimensions " << frameBounds.width << 'x' << frameBounds.height
                      << "; client origin " << originX << ',' << originY << '\n';
            const auto region = captureRegion(display, selected, decorated);
            const int outputWidth = 40;
            const int outputHeight = (region.height * outputWidth + region.width / 2) / region.width;
            const int clientY = (originY - region.y + 30) * outputHeight / region.height;
            const int clientX = (originX - region.x + 40) * outputWidth / region.width;
            WindowStream stream(display, selected, getpid(), outputWidth, outputHeight);
            auto frame = stream.next();
            require(frame.size() == 24 + size_t(outputWidth) * outputHeight * 4 &&
                    std::memcmp(frame.data(), "BSC1", 4) == 0, "frame protocol mismatch");
            auto center = 24 + (clientY * outputWidth + clientX) * 4;
            std::cout << "Selected center BGR " << unsigned(frame[center]) << ','
                      << unsigned(frame[center + 1]) << ',' << unsigned(frame[center + 2]) << '\n';
            require(frame[center] == 0xca && frame[center + 1] == 0x56 && frame[center + 2] == 0x12,
                    "capture leaked occluder or lost selected pixels");
            // Drain the initial damage before checking idle capture and a final changed frame.
            if (stream.hasChanges()) stream.next();
            XSync(display, False);
            require(!stream.hasChanges(), "unchanged window should be idle");
            XSetWindowBackground(display, selected, 0x27a845);
            XClearWindow(display, selected);
            XSync(display, False);
            require(stream.hasChanges(), "last changed frame must be noticed");
            auto changed = stream.next();
            require(changed[center] == 0x45 && changed[center + 1] == 0xa8 && changed[center + 2] == 0x27,
                    "changed pixels were lost");
            auto paint = XCreateGC(display, selected, 0, nullptr);
            XSetForeground(display, paint, 0xff0000);
            XFillRectangle(display, selected, paint, 0, 0, 40, 60);
            XSetForeground(display, paint, 0x0000ff);
            XFillRectangle(display, selected, paint, 40, 0, 40, 60);
            XFreeGC(display, paint);
            XSync(display, False);
            require(stream.hasChanges(), "scaled content update must be noticed");
            auto scaled = stream.next();
            const int leftX = (originX - region.x + 10) * outputWidth / region.width;
            const int rightX = (originX - region.x + 70) * outputWidth / region.width;
            auto left = 24 + (clientY * outputWidth + leftX) * 4;
            auto right = 24 + (clientY * outputWidth + rightX) * 4;
            std::cout << "Scaled left BGR " << unsigned(scaled[left]) << ','
                      << unsigned(scaled[left + 1]) << ',' << unsigned(scaled[left + 2])
                      << "; right " << unsigned(scaled[right]) << ','
                      << unsigned(scaled[right + 1]) << ',' << unsigned(scaled[right + 2]) << '\n';
            require(scaled[left + 2] == 255 && scaled[left] == 0 &&
                scaled[right + 2] == 0 && scaled[right] == 255, "server scaling lost spatial content");
            bool rejected = false;
            try { WindowStream invalid(display, selected, getpid() + 100000, 40, 30); }
            catch (const std::exception&) { rejected = true; }
            require(rejected, "foreign process source admitted");
            rejected = false;
            try { WindowStream distorted(display, selected, getpid(), outputWidth, outputHeight * 2); }
            catch (const std::exception&) { rejected = true; }
            require(rejected, "incompatible host aspect ratio distorted the native source");
            XResizeWindow(display, selected, 100, 60);
            XSync(display, False);
            rejected = false;
            try { stream.next(); }
            catch (const std::exception&) { rejected = true; }
            require(rejected, "resize retained stale geometry");
        }
        XDestroyWindow(display, unrelated);
        XDestroyWindow(display, selected);
        XSync(display, False);
        std::cout << "Exact X11 source pixels, occlusion, PID rejection, resize: passed\n";
        return 0;
    } catch (const std::exception& failure) {
        std::cerr << failure.what() << '\n';
        return 1;
    }
}
