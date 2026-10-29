// okay-faces' iOS face: the C functions of an application's Scala Native library (specs/ios.md)
#ifndef OKAY_FACES_H
#define OKAY_FACES_H

int ScalaNativeInit(void);                      // once, before anything
char* okay_faces_open(const char* app);         // the application by its class name; the first screen (JSON)
char* okay_faces_act(const char* choice);       // {"action": name, "form": {"a.b": ["v"]}}; the next screen
char* okay_faces_back(void);                    // the step before
char* okay_faces_screen(void);                  // the screen now: after `moved`, what the event moved it to
char* okay_faces_look(void);                    // the application's look (JSON)
void okay_faces_on_moved(void (*moved)(void));  // told when an event moved the dialogue on by itself
void okay_faces_free(char* s);                  // every string returned is the caller's, freed here

#endif
