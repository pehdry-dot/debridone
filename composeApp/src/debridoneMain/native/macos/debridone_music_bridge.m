// DebridOne Music: macOS integration for the Music section's audio player.
//
// This library exists for two reasons:
//
//  1. It is linked against the bundled libmpv with an @loader_path search path. Opening it first
//     lets the dynamic loader find libmpv and libmpv's own dependencies (FFmpeg and friends, which
//     are referenced through @rpath) in the application's native directory. Music then talks to
//     libmpv directly; it never goes through Streaming's video player bridge.
//
//  2. It publishes what Music is playing to macOS (Control Centre, the Now Playing widget, the
//     lock screen) and receives the media keys and remote commands.
//
// Original DebridOne code. Plain C entry points, called from Kotlin through JNA.

#import <AppKit/AppKit.h>
#import <Foundation/Foundation.h>
#import <MediaPlayer/MediaPlayer.h>

#include <mpv/client.h>

#define DEBRIDONE_EXPORT __attribute__((visibility("default")))

// Commands delivered to the handler. Keep in sync with MusicMacBridge.kt.
enum {
    DebridOneMusicCommandPlay = 1,
    DebridOneMusicCommandPause = 2,
    DebridOneMusicCommandToggle = 3,
    DebridOneMusicCommandNext = 4,
    DebridOneMusicCommandPrevious = 5,
    DebridOneMusicCommandSeek = 6,
    DebridOneMusicCommandStop = 7,
};

typedef void (*debridone_music_command_handler)(int command, double value);

static debridone_music_command_handler gHandler = NULL;
// Pairs of (MPRemoteCommand, target token) registered by this library, so that only our own
// targets are ever removed.
static NSMutableArray<NSArray *> *gTargets = nil;
static NSString *gArtworkPath = nil;
static MPMediaItemArtwork *gArtwork = nil;

static void debridone_run_on_main(dispatch_block_t block) {
    if ([NSThread isMainThread]) {
        block();
    } else {
        dispatch_async(dispatch_get_main_queue(), block);
    }
}

static NSString *debridone_string(const char *value) {
    if (value == NULL || value[0] == '\0') {
        return nil;
    }
    return [NSString stringWithUTF8String:value];
}

static void debridone_remove_targets(void) {
    for (NSArray *pair in gTargets) {
        MPRemoteCommand *command = pair[0];
        [command removeTarget:pair[1]];
    }
    gTargets = nil;
}

static void debridone_add_target(MPRemoteCommand *command, int code) {
    command.enabled = YES;
    id token = [command addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *event) {
        debridone_music_command_handler handler = gHandler;
        if (handler == NULL) {
            return MPRemoteCommandHandlerStatusCommandFailed;
        }
        double value = 0.0;
        if ([event isKindOfClass:[MPChangePlaybackPositionCommandEvent class]]) {
            value = ((MPChangePlaybackPositionCommandEvent *)event).positionTime;
        }
        handler(code, value);
        return MPRemoteCommandHandlerStatusSuccess;
    }];
    if (token != nil) {
        [gTargets addObject:@[command, token]];
    }
}

// The client API version of the libmpv this library was loaded with. Proves the link works.
DEBRIDONE_EXPORT unsigned long debridone_music_mpv_api_version(void) {
    return mpv_client_api_version();
}

DEBRIDONE_EXPORT void debridone_music_set_command_handler(debridone_music_command_handler handler) {
    gHandler = handler;
}

// Registers Music for the media keys and remote commands. Safe to call repeatedly: the video
// player removes every target when it finishes, so Music re-registers when it plays again.
DEBRIDONE_EXPORT void debridone_music_activate_commands(void) {
    debridone_run_on_main(^{
        debridone_remove_targets();
        gTargets = [NSMutableArray array];
        MPRemoteCommandCenter *center = [MPRemoteCommandCenter sharedCommandCenter];
        debridone_add_target(center.playCommand, DebridOneMusicCommandPlay);
        debridone_add_target(center.pauseCommand, DebridOneMusicCommandPause);
        debridone_add_target(center.togglePlayPauseCommand, DebridOneMusicCommandToggle);
        debridone_add_target(center.nextTrackCommand, DebridOneMusicCommandNext);
        debridone_add_target(center.previousTrackCommand, DebridOneMusicCommandPrevious);
        debridone_add_target(center.changePlaybackPositionCommand, DebridOneMusicCommandSeek);
        debridone_add_target(center.stopCommand, DebridOneMusicCommandStop);
    });
}

// Gives the media keys up (a video is about to play). Does not touch the now-playing information,
// which by then belongs to whoever took over.
DEBRIDONE_EXPORT void debridone_music_deactivate_commands(void) {
    debridone_run_on_main(^{
        debridone_remove_targets();
    });
}

DEBRIDONE_EXPORT void debridone_music_set_now_playing(
    const char *title,
    const char *artist,
    const char *album,
    double durationSeconds,
    double positionSeconds,
    int playing,
    const char *artworkPath
) {
    // Copy the C strings now: the caller frees them as soon as this function returns.
    NSString *titleString = debridone_string(title);
    NSString *artistString = debridone_string(artist);
    NSString *albumString = debridone_string(album);
    NSString *artworkString = debridone_string(artworkPath);

    debridone_run_on_main(^{
        NSMutableDictionary *info = [NSMutableDictionary dictionary];
        if (titleString != nil) {
            info[MPMediaItemPropertyTitle] = titleString;
        }
        if (artistString != nil) {
            info[MPMediaItemPropertyArtist] = artistString;
        }
        if (albumString != nil) {
            info[MPMediaItemPropertyAlbumTitle] = albumString;
        }
        if (durationSeconds > 0.0) {
            info[MPMediaItemPropertyPlaybackDuration] = @(durationSeconds);
        }
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = @(positionSeconds < 0.0 ? 0.0 : positionSeconds);
        info[MPNowPlayingInfoPropertyPlaybackRate] = @(playing ? 1.0 : 0.0);
        info[MPNowPlayingInfoPropertyMediaType] = @(MPNowPlayingInfoMediaTypeAudio);

        if (artworkString == nil) {
            gArtworkPath = nil;
            gArtwork = nil;
        } else if (![artworkString isEqualToString:gArtworkPath]) {
            gArtworkPath = artworkString;
            gArtwork = nil;
            NSImage *image = [[NSImage alloc] initWithContentsOfFile:artworkString];
            if (image != nil && image.size.width > 0 && image.size.height > 0) {
                gArtwork = [[MPMediaItemArtwork alloc] initWithBoundsSize:image.size
                                                           requestHandler:^NSImage *(CGSize size) {
                    return image;
                }];
            }
        }
        if (gArtwork != nil) {
            info[MPMediaItemPropertyArtwork] = gArtwork;
        }

        MPNowPlayingInfoCenter *center = [MPNowPlayingInfoCenter defaultCenter];
        center.nowPlayingInfo = info;
        center.playbackState = playing ? MPNowPlayingPlaybackStatePlaying : MPNowPlayingPlaybackStatePaused;
    });
}

// Music has nothing queued any more.
DEBRIDONE_EXPORT void debridone_music_clear_now_playing(void) {
    debridone_run_on_main(^{
        debridone_remove_targets();
        gArtworkPath = nil;
        gArtwork = nil;
        MPNowPlayingInfoCenter *center = [MPNowPlayingInfoCenter defaultCenter];
        center.nowPlayingInfo = nil;
        center.playbackState = MPNowPlayingPlaybackStateStopped;
    });
}
