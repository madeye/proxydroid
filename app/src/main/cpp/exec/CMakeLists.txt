add_library(exec SHARED
	    termExec.cpp)

# Include libraries needed for exec lib
target_link_libraries(exec
                      android
		      dl
                      log)
